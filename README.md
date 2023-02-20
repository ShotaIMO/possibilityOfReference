# possibilityOfReference

##　はじめに
今回のコードは2023年2月22に開催されたCorda Tech Meetup 冬の陣で行った"Reference Stateの可能性"にて使用したコードになります。
※このコードは"調査2: 複数のRef.stateを含めた場合、Notaryが検知したRef.stateはログにて全て確認できるか"を調査するコードになります。
今回は、Corda trainingのコードにRef.stateとしてAddressStateという住所を表すstateを追加しました。

##　起動手順
1. はじめにRef.stateとして扱うAddressStateを発行するためPublishFlowを実行します。引数には"Party: 発行者"と"Address: 住所"を指定します
2. 
