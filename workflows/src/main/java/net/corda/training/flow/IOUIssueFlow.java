package net.corda.training.flow;

import co.paralleluniverse.fibers.Suspendable;
import net.corda.core.contracts.*;
import net.corda.core.crypto.SecureHash;
import net.corda.core.flows.*;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.training.contracts.IOUContract;
import net.corda.training.flow.utilities.InstanceGenerateFlow;
import net.corda.training.states.AddressState;
import net.corda.training.states.IOUState;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static net.corda.core.contracts.ContractsDSL.requireThat;
import static net.corda.training.contracts.IOUContract.Commands.Issue;

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Add code regarding Ref.State(AddressState) to investigate
 * whether Ref.State(AddressState) can include in transaction or not.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
public class IOUIssueFlow {

    @InitiatingFlow(version = 2)
    @StartableByRPC
    public static class InitiatorFlow extends FlowLogic<SignedTransaction> {

        private final String currency;
        private final long amount;
        private final Party lender;
        private final Party borrower;
        private final Party addressStateIssuer;

        public InitiatorFlow(String currency, long amount, Party lender, Party borrower,Party addressStateIssuer) {
            this.currency = currency;
            this.amount = amount;
            this.lender = lender;
            this.borrower = borrower;
            this.addressStateIssuer=addressStateIssuer;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {

            // 1. Make sure that the Party of the lender and the executing node are equal.
            if ( !borrower.equals(getOurIdentity())){
                throw new FlowException("The Party of the borrower and the executing node are different..");
            }
            //2. Set Transaction Hash here to search Ref.State
            SecureHash addressHash1=getFirstUnconsumedRefState(addressStateIssuer).getRef().getTxhash();
            SecureHash addressHash2=getSecondUnconsumedRefState(addressStateIssuer).getRef().getTxhash();

            //2-1. trace back to search previous Ref.State in the first chain.
            StateRef results1 =getServiceHub().getValidatedTransactions().getTransaction(addressHash1).getInputs().get(0);
            SecureHash previousHash1=results1.getTxhash();

            //2-2. trace back to search previous Ref.State in the second chain.
            StateRef results2 =getServiceHub().getValidatedTransactions().getTransaction(addressHash2).getInputs().get(0);
            SecureHash previousHash2=results2.getTxhash();

            //2-3. set each previous Ref.State's StateAndRef.
            StateAndRef<AddressState> addressBody1=getConsumedRefState(previousHash1);
            StateAndRef<AddressState> addressBody2=getConsumedRefState(previousHash2);

            //3. create IOUState
            final IOUState state = subFlow(new InstanceGenerateFlow(currency, amount, lender, borrower));

            // 4. Get a reference to the notary service on our network and our key pair.
            // Note: ongoing work to support multiple notary identities is still in progress.
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

            // 5. Create a new issue command.
            // Remember that a command is a CommandData object and a list of CompositeKeys
            final Command<Issue> issueCommand = new Command<>(
                    new Issue(), state.getParticipants()
                    .stream().map(AbstractParty::getOwningKey)
                    .collect(Collectors.toList()));

            // 6. Create a new TransactionBuilder object.
            final TransactionBuilder builder = new TransactionBuilder(notary);

            // 7. Add the iou as an output state and AddressState, as well as a command to the transaction builder.
            builder.addOutputState(state, IOUContract.IOU_CONTRACT_ID);
            builder.addCommand(issueCommand);

            if(addressBody1!=null){
                builder.addReferenceState(new ReferencedStateAndRef<>(addressBody1));
            }
            if(addressBody2!=null){
                builder.addReferenceState(new ReferencedStateAndRef<>(addressBody2));
            }

            // 7. Verify and sign it with our KeyPair.
            builder.verify(getServiceHub());
            final SignedTransaction ptx = getServiceHub().signInitialTransaction(builder);

            // 8. Collect the other party's signature using the SignTransactionFlow.
            List<Party> otherParties = state.getParticipants()
                    .stream().map(el -> (Party)el)
                    .collect(Collectors.toList());

            otherParties.remove(getOurIdentity());

            List<FlowSession> sessions = otherParties
                    .stream().map(el -> initiateFlow(el))
                    .collect(Collectors.toList());

            SignedTransaction stx = subFlow(new CollectSignaturesFlow(ptx, sessions));

            try{
                // 9. Assuming no exceptions, we can now finalise the transaction
                return subFlow(new FinalityFlow(stx, sessions));

            }catch(net.corda.core.flows.NotaryException e){
                //if "NotaryException" is detected, following process will be executed.
                //get error message from the error object.
                String str=e.getMessage();

                //if previous ref.State is included in the error object, display hash and index into Node terminal window.
                //Note. Regarding first ref.state chain.
                if(str.contains(String.valueOf(previousHash1))){
                    SecureHash firstly_Published_RefState=previousHash1;
                    System.out.println("Firstly published Ref.State is "+firstly_Published_RefState);

                    int index_First_refState=addressBody1.getRef().getIndex();
                    if(str.contains(String.valueOf(index_First_refState))){
                        System.out.println("Firstly published Ref.State's index is "+index_First_refState);
                    }
                    else{
                        System.out.println("Cannot find Ref.State whose index is "+index_First_refState);
                    }
                }else {
                    System.out.println(previousHash1+" is not included in the error object.");
                }

                //if previous ref.State is included in the error object, display hash and index into Node terminal window.
                //Note. Regarding second ref.state chain.
                if(str.contains(String.valueOf(previousHash2))){
                    SecureHash secondly_Published_RefState=previousHash2;
                    System.out.println("Secondly published Ref.State is "+secondly_Published_RefState);

                    int index_Second_refState=addressBody2.getRef().getIndex();
                    if(str.contains(String.valueOf(index_Second_refState))){
                        System.out.println("Secondly published Ref.State's index is "+index_Second_refState);
                    }else{
                        System.out.println("Cannot find Ref.State whose index is "+index_Second_refState);
                    }
                }else {
                    System.out.println(previousHash2+" is not included in the error object.");
                }
                return null;
            }
        }


        /**
         * This is the function which confirm whether the ref.state of purpose is truly included in vault query.
         * In short, we can get firstly "moved" ref.state.
         * @return
         */
        @Suspendable
        public StateAndRef<AddressState> getFirstUnconsumedRefState(Party addressStateIssuer){

            Predicate<StateAndRef<AddressState>> byIssuer=addressISU
                    ->(addressISU.getState().getData().getIssuer().equals(addressStateIssuer));
            List<StateAndRef<AddressState>> addressLists = getServiceHub().getVaultService().queryBy(AddressState.class)
                    .getStates().stream().filter(byIssuer).collect(Collectors.toList());
            if(addressLists.isEmpty()){
                return null;
            }else{
                return addressLists.get(0);
            }
        }
        /**
         * This is the function which confirm whether the ref.state of purpose is truly included in vault query.
         * In short, we can get secondly "moved" ref.state.
         * @return
         */
        @Suspendable
        public StateAndRef<AddressState> getSecondUnconsumedRefState(Party addressStateIssuer){

            Predicate<StateAndRef<AddressState>> byIssuer=addressISU
                    ->(addressISU.getState().getData().getIssuer().equals(addressStateIssuer));
            List<StateAndRef<AddressState>> addressLists = getServiceHub().getVaultService().queryBy(AddressState.class)
                    .getStates().stream().filter(byIssuer).collect(Collectors.toList());
            if(addressLists.isEmpty()){
                return null;
            }else{
                return addressLists.get(1);
            }
        }

        /**
         * This is the function which search previous ref.state.
         * In short, we can get previous ref.state.
         * @return
         */
        @Suspendable
        public StateAndRef<AddressState> getConsumedRefState(SecureHash previousHash){
            QueryCriteria queryCriteria=new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL);
            //search the hash equal to the previous hash and put it in the list.
            Predicate<StateAndRef<AddressState>> byHash=address_hash
                    ->(address_hash.getRef().getTxhash().equals(previousHash));
            List<StateAndRef<AddressState>> addressHashLists=getServiceHub().getVaultService()
                    .queryBy(AddressState.class,queryCriteria)
                    .getStates().stream().filter(byHash).collect(Collectors.toList());
            if(addressHashLists.isEmpty()){
                return null;
            }else{
                return addressHashLists.get(0);
            }
        }
    }

    /**
     * This is the flow which signs IOU issuances.
     * The signing is handled by the [SignTransactionFlow].
     */
    @InitiatedBy(IOUIssueFlow.InitiatorFlow.class)
    public static class ResponderFlow extends FlowLogic<SignedTransaction> {

        private final FlowSession flowSession;
        private SecureHash txWeJustSigned;

        public ResponderFlow(FlowSession flowSession){
            this.flowSession = flowSession;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {

            class SignTxFlow extends SignTransactionFlow {

                private SignTxFlow(FlowSession flowSession, ProgressTracker progressTracker) {
                    super(flowSession, progressTracker);
                }

                @Override
                protected void checkTransaction(SignedTransaction stx) {
                    requireThat(req -> {
                        ContractState output = stx.getTx().getOutputs().get(0).getData();
                        req.using("This must be an IOU transaction", output instanceof IOUState);
                        return null;
                    });
                    // Once the transaction has verified, initialize txWeJustSignedID variable.
                    txWeJustSigned = stx.getId();
                }
            }

            flowSession.getCounterpartyFlowInfo().getFlowVersion();

            // Create a sign transaction flow
            SignTxFlow signTxFlow = new SignTxFlow(flowSession, SignTransactionFlow.Companion.tracker());

            // Run the sign transaction flow to sign the transaction
            subFlow(signTxFlow);

            // Run the ReceiveFinalityFlow to finalize the transaction and persist it to the vault.
            return subFlow(new ReceiveFinalityFlow(flowSession, txWeJustSigned));

        }
    }
}


