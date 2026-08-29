# Commit

検証が通ったら、PR を作らず `main` へ commit / push します。

## Before commit

- Acceptance Criteria が確認済み
- `gradle-run` 経由の `test` が PASS
- 必須実機 Verification が PASS
- blocking finding なし
- Risk が要求する Review があるなら PASS

必須実機確認が BLOCKED のまま完了扱いの commit をしない。

## Commit

- 通常は `main` で作業する
- 小さな目的単位で commit する
- commit message に未実施の検証を PASS と書かない
- secret を入れない
- 通ったら `origin/main` へ push する

## Do not

- PR を作る（ユーザーが明示した時だけ）
- ブランチを切って merge-ready 待ちする
- unit test や必須実機確認を飛ばして完了にする
