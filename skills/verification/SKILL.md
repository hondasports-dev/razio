# Verification

RAZIOのVerificationは、Android実機での短いフィードバックループを中心にします。

## Default order

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
adb devices
adb install -r <debug-apk>
```

その後、変更したアプリ/画面/音声経路を実機で操作します。

## Device check

最低限確認するもの:

- APKが起動する
- 変更した操作が実際に踏める
- crash / ANR / obvious errorがない
- relevantなlogcat errorがない
- AudioEffect変更ならeffect生成・enable状態が確認できる
- system-wide audioを狙う変更なら対象の他アプリ音声で実際に変化を確認する

必要な時だけ `dumpsys audio`、`dumpsys media.audio_flinger` 等を追加します。

## Fail fast

- unit/static/build失敗時はinstallへ進まない
- install/launch失敗時はUI確認へ進まない
- 同じコマンドを理由なく繰り返さず、出力を読んで原因を直す
- 修正後は失敗地点に最も近いcheap checkから再開する

## Evidence

PASSには実行したcommand、対象端末、確認したbehavior、重要なlog結果をcompactに残します。

実機が必要な変更で端末が提供されていない場合はBLOCKEDです。NOT_REQUIREDやPASSにはしません。
