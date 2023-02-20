# possibilityOfReference

##　はじめに
今回のコードは2023年2月22に開催されたCorda Tech Meetup 冬の陣で行った"Reference Stateの可能性"にて使用したコードになります。
※このコードは"調査2: 複数のRef.stateを含めた場合、Notaryが検知したRef.stateはログにて全て確認できるか"を調査するコードになります。
今回は、Corda trainingのコードにRef.stateとしてAddressStateという住所を表すstateを追加しました。

##　起動手順
1. はじめにRef.stateとして扱うAddressStateを発行するためPublishFlowを実行します。
   引数には"Party: 発行者"と"Address: 住所"を指定します。
2. 続いて、AddressStateを更新するためMoveFlowを実行します。
   引数には"Address: 住所"と"linearId: 先ほど発行したAddressStateのLInearId"を指定します
   ※少し手間ですが、複数のRef.stateを発行したい場合、PublishFlow→MoveFlowというプロセスを複数回繰り返してください。
3. それでは、IOUのIssueトランザクションの発行時にAddressStateをRef.stateとして含めたいと思います
   IOUIssueFlowを実行します。
   ※この時CordaTrainingのコードと異なり3つの引数が新たに追加されています。
   ※addressStateIssuer: AddressStateの発行者, number: 含めるRef.stateの数(5つ存在するならば5と指定), previous: ひとつ前のAddressState(Publishした時点でのRef.state)を含めたい場合は"true"を、最新のAddressState(MoveしたRef.state)を含めたい場合はfalseを指定
