# Incident

同じ失敗を繰り返す前に原因を分類します。

## Classify

- code / compile
- test
- Gradle / dependency
- adb connection
- install / launch
- permission / lifecycle
- AudioEffect / routing / device-specific
- CI / environment

## Action

1. 最初の失敗Evidenceを保存する。
2. logcat / Gradle output / dumpsysなど最も近い一次情報を読む。
3. 仮説を1つ立て、最小変更または診断commandで検証する。
4. 原因が変わっていない状態で同じretryを繰り返さない。
5. 解消後は失敗地点の直前から通常Verificationへ戻る。

環境問題を理由に必須実機VerificationをPASS扱いしません。
