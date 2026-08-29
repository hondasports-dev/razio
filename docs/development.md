# Development

## 前提環境

- JDK 17 以上（ローカルは Temurin 21。AGP 9 の要求は 17）
- Android SDK Command-line Tools（Android Studio でも可）
- platform-tools（`adb`）
- Android SDK Platform 37.0（compileSdk 37。`platforms;android-37.0`）
- Build-Tools 36.0.0（AGP 9.1 の default）
- Gradle Wrapper 9.5.0（リポジトリ同梱。システム Gradle は使わない）
- Android 実機（AudioEffect の成立判定用。エミュレーターだけでは判定しない）

Windows で Android Studio なしの場合の例:

```powershell
scoop install android-clt
sdkmanager --sdk_root=$env:ANDROID_HOME "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

`ANDROID_HOME` / `ANDROID_SDK_ROOT` は SDK ルートを指す。このマシンでは:

```text
%USERPROFILE%\scoop\apps\android-clt\current
```

プロジェクトの `local.properties` の `sdk.dir` でも解決できる。このファイルは commit しない。

## ツールバージョンの揃え方

「最新」より **Kotlin / AGP / Gradle の公式サポート交差** を優先する。2026-08 時点の採用セット:

| ツール | 採用 | 上げない理由 |
| --- | --- | --- |
| Kotlin | 2.4.10 | KGP 2.4.10 の fully supported 上限 |
| AGP | 9.1.1 | KGP の fully supported 上限は 9.1.0。9.1.1 は API 37 用の 9.1 パッチ。9.2/9.3 は Kotlin 未サポート |
| Gradle Wrapper | 9.5.0 | KGP 2.4.10 の fully supported 上限。AGP 9.1 の最低は 9.3.1 だが、9.2–9.3 は Windows で settings.kts の `plugins` が壊れる |
| compileSdk | 37 | core-ktx 1.19.0 が要求。AGP 9.1.1 の上限は API 37.0 |
| targetSdk | 36 | compileSdk とは独立。実行時挙動のオプトインは後回し |
| Compose BOM | 2026.06.01 | 公式 mapping の最新安定。1.12 系 BOM は mapping に出てから |
| core-ktx | 1.19.0 | compileSdk 37 が必要 |
| activity-compose | 1.13.0 | Compose セットアップ文書の現行安定 |
| JDK | 17 bytecode / 実行は 21 | AGP 9 の要求は 17。25 には上げない |

AGP 9.3 + Compose 1.12 は、Kotlin の fully supported 表が追いついてからにする。

cmdline-tools が SDK XML v4 を出す一方、AGP 9.1.1 は v3 までしか読まないため、ビルド時に警告が出ることがある。失敗ではない。AGP を 9.2 以降へ上げると消える可能性があるが、その時点では Kotlin の AGP 上限と衝突する。

RAZIO は AudioEffect の端末依存性を検証する必要があるため、エミュレーターだけでは成立判定をしません。

## 識別子

- package / namespace / applicationId: `dev.hondasports.razio`
- launcher activity: `dev.hondasports.razio.MainActivity`

## 開発フロー

基本ループ:

```text
実装
    ↓
./gradlew test
    ↓
./gradlew assembleDebug
    ↓
adb install -r
    ↓
実機で対象アプリを再生 / 変更箇所を操作
    ↓
logcat / dumpsys
    ↓
結果を docs に記録
    ↓
main へ commit / push
```

PR は作らない。unit test または必須実機確認が落ちている間は commit して完了にしない。

## よく使うコマンド

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.hondasports.razio/.MainActivity
adb logcat
```

Windows では `gradlew.bat` を使う。

## 実機接続の確認

```bash
adb devices -l
```

複数端末が接続されている場合は `-s SERIAL` を付けます。

```bash
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
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

## Agent skills

Android / Kotlin / Compose 向けの agent skill は `npx skills` でプロジェクトへ入れている。実体は `.agents/skills/`、lock は `skills-lock.json`。

入っているもの:

- `android/skills`: `android-cli`, `testing-setup`, `edge-to-edge`, `android-profiler`
- `chrisbanes/skills`: Compose state / component / UI test、Kotlin API / control-flow / coroutines-flow、`gradle-run`

更新:

```bash
npx skills update
```

Navigation 3、CameraX、Wear、Play Billing、Firebase は今の RAZIO に不要なので入れていません。必要になったらその時だけ足す。

既存の `skills/`（PREPARE / IMPLEMENT / VERIFY / COMMIT などの Loop skill）とは別物です。Loop の正本は変わらない。

## MCP

MCP は必須ではありません。

Codex など CLI を操作できるエージェントなら、Gradle と ADB を直接実行する方を最初の構成とします。

ADB MCP を導入するのは、以下を自律化したくなった場合です。

- UI hierarchy の取得
- screenshot
- tap / swipe
- アプリ操作と logcat の反復

MCP 自体を導入することを目的化しないでください。

## ブランチ / commit

原則:

- 通常は `main` で実装する
- PR は作らない
- 小さな目的単位で commit し、`origin/main` へ push する
- AudioEffect の成立性に関わる変更では実機結果を commit 前に確認し、`docs/audio-research.md` に残す

commit 前に嘘をつかないこと:

- 自動テスト: 実施済み / 未実施
- 実機テスト: 実施済み / 未実施
- 未実施なら BLOCKED。完了扱いにしない
