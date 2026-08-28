# PR Aftercare

PR作成後も、latest revisionがmerge-readyになるまで追います。

## Loop

1. CI / checksを確認する。
2. failed checkはlogを読み、原因を修正する。
3. review comment / requested changesを仕様・テスト・Android制約と照合する。
4. 必要な修正を行う。
5. 変更差分に必要なVerificationだけ再実行する。
6. conflict / stale revisionがないことを確認する。

## Rules

- CI失敗を盲目的rerunしない
- review提案を無条件で採用しない
- contentが変わったら、そのdeltaに必要なdevice/test Evidenceを更新する
- latest revisionのrequired checksが成功して初めてmerge-readyとする
