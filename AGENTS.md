# AGENTS.md

このリポジトリで作業する AI エージェント向けの実装ルールです。

## 1. プロジェクトの目的

RAZIO は、Android 端末で再生される音声を昔の AM ラジオのような音質へ変換する個人向けアプリです。

最重要条件:

- root 化を前提にしない
- 他アプリの音声も対象にしたい
- Android の制約を推測で回避したことにしない
- 実機で成立性を確認してから本実装へ進む

## 2. 技術スタック

原則として以下を使用します。

- Kotlin
- Jetpack Compose
- Gradle Kotlin DSL
- AndroidX
- Android AudioEffect API
- JUnit
- ADB / logcat / dumpsys

新しい依存関係は、標準 API で代替できない理由がある場合だけ追加してください。

## 3. 最優先の開発順序

実装開始時は、見栄えの良い UI より先に音声経路の成立性を検証してください。

優先順位:

1. Android プロジェクトを build 可能にする
2. audio session `0` に対する global AudioEffect の PoC を作る
3. 実機で YouTube / 音楽アプリ等への影響を確認する
4. logcat と dumpsys で挙動を記録する
5. 成立する場合のみ AM プリセットを実装する
6. 成立しない場合は AudioPlaybackCapture の代替案を検証する
7. UI は音声経路が成立した後に作り込む

## 4. 実装ルール

### 4.1 AudioEffect

- deprecated API を使う場合は、利用理由と代替案をコードコメントまたは docs に残す
- session `0` が常に動くと仮定しない
- API レベル、端末、出力先ごとの差を切り分けられる設計にする
- effect の生成失敗・無効化・未対応を UI で判別できるようにする

### 4.2 DSP

AM ラジオ風の初期目標は、実際の AM 変調そのものではなく「受信機 + 小型スピーカーの聴感」を再現することです。

初期プリセットの目安:

- 250 Hz 以下: 強く減衰
- 3.5 kHz 以上: 強く減衰
- 1〜2 kHz: 軽く強調
- Compression: 強め
- Limiter: 有効

ノイズ、クラックル、フェージングなどの装飾 DSP は、global AudioEffect の成立性確認後に検討します。

### 4.3 UI

- Jetpack Compose を使う
- まずは ON/OFF、状態表示、プリセット選択だけでよい
- 音声処理が未対応の端末では、失敗を隠さず表示する

## 5. AI エージェントの作業ループ

各タスクでは以下を基本ループにします。

1. 変更対象を確認
2. 最小差分で実装
3. `./gradlew test`
4. `./gradlew assembleDebug`
5. 実機が接続されている場合は `adb devices`
6. APK を `adb install -r` で導入
7. アプリ起動
8. logcat を確認
9. 必要に応じて dumpsys で AudioEffect / audio 状態を確認
10. 問題があれば修正して再実行

テスト不能な場合は、単に「実行不能なので先へ進む」のではなく、まず実行可能にするための不足条件を確認してください。

## 6. 禁止事項

- root 前提の実装へ勝手に変更しない
- custom ROM 前提にしない
- AudioPlaybackCapture なら必ず元音声を抑制できる、と仮定しない
- エミュレーターだけで端末全体への効果を成立判定しない
- deprecated API を警告なく採用しない
- UI 完成を音声 PoC より優先しない

## 7. ドキュメント更新

実機検証で以下が分かった場合は `docs/audio-research.md` を更新してください。

- 端末名
- Android バージョン
- 出力先
- 対象アプリ
- AudioEffect が効いたか
- 例外 / logcat
- 再現手順
- 判断結果

設計判断が変わった場合は `docs/architecture.md` と `docs/roadmap.md` も更新してください。
