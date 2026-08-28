# Development

## 前提環境

- Android Studio または Android SDK
- JDK（使用する Android Gradle Plugin が要求するバージョン）
- ADB
- Gradle Wrapper
- Android 実機

RAZIO は AudioEffect の端末依存性を検証する必要があるため、エミュレーターだけでは成立判定をしません。

## 開発フロー

基本ループ:

```text
Issue / task
    ↓
AI agent implements
    ↓
./gradlew test
    ↓
./gradlew assembleDebug
    ↓
adb install -r
    ↓
実機で対象アプリを再生
    ↓
RAZIO ON / OFF
    ↓
logcat / dumpsys
    ↓
結果を docs に記録
    ↓
修正 or 次のタスク
```

## よく使うコマンド

```bash
./gradlew test
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat
```

package name が決定したら、起動コマンドも固定します。

```bash
adb shell am start -n <package>/<activity>
```

## 実機接続の確認

```bash
adb devices -l
```

複数端末が接続されている場合は `-s SERIAL` を付けます。

```bash
adb -s SERIAL install -r app-debug.apk
```

## Logcat

AudioEffect の初期化失敗を追えるように、アプリ側でも明示的なタグを使います。

候補:

```text
RAZIO/AudioEffect
RAZIO/Preset
RAZIO/Diagnostics
```

通常運用では秘密情報をログへ出さないでください。

## dumpsys

端末の audio 状態確認に利用します。

```bash
adb shell dumpsys audio
adb shell dumpsys media.audio_flinger
adb shell dumpsys media.audio_policy
```

利用できる dumpsys service は Android バージョンや端末によって異なるため、コマンド失敗を即座にアプリ不具合とは判断しません。

## AI / Codex に任せる範囲

AI エージェントには以下を任せて構いません。

- プロジェクト生成
- Kotlin 実装
- Compose UI
- unit test
- Gradle build
- lint
- APK install
- app launch
- logcat 解析
- dumpsys 解析
- ドキュメント更新

人間が判断する項目:

- 実際に AM ラジオらしく聞こえるか
- 許容できる音量差か
- 端末で他アプリに効いているかの最終聴感確認
- プロダクトとしての UX

## MCP

MCP は必須ではありません。

Codex など CLI を操作できるエージェントなら、Gradle と ADB を直接実行する方を最初の構成とします。

ADB MCP を導入するのは、以下を自律化したくなった場合です。

- UI hierarchy の取得
- screenshot
- tap / swipe
- アプリ操作と logcat の反復

MCP 自体を導入することを目的化しないでください。

## ブランチ / PR

原則:

- `main` へ直接実装しない
- 小さな目的単位で branch を作る
- PR に検証結果を記載する
- AudioEffect の成立性に関わる変更では実機結果を必ず明記する

PR の最低記載事項:

- What
- Why
- Test
- Device / Android version（実機検証時）
- Known limitations
