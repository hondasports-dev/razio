# Process Learning

通常タスクのたびには起動しません。繰り返し使える学びが出た時だけ起動します。

## Trigger examples

- 同じadb / Gradle / install失敗を複数回踏んだ
- 実機確認の手順をscript化できる
- 不要なGateや重複checkが見つかった
- Android端末依存の知見を再利用できる

## Priority

改善は追加手順より先に、次を検討します。

1. 手順を削る
2. 手順を統合する
3. cheap checkを前へ移す
4. script / deterministic automationにする
5. contextを減らす
6. device feedbackを速くする

候補は最大3件。通常タスクへ新しい重いGateを追加すること自体を改善と見なしません。
