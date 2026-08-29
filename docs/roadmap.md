# Roadmap

## Phase 0: Repository bootstrap

目的: AI / 人間のどちらでも実装開始できる状態にする。

- [x] Android プロジェクト作成
- [x] Kotlin + Jetpack Compose
- [x] Gradle Wrapper
- [x] package name 決定（`dev.hondasports.razio`）
- [x] unit test が実行可能
- [x] debug APK が build 可能
- [x] CI の最小構成

完了条件:

```bash
./gradlew test
./gradlew assembleDebug
```

が成功すること。

## Phase 1: Global AudioEffect PoC

最重要フェーズ。

実装:

- [ ] session `0` で Equalizer を生成
- [ ] session `0` で DynamicsProcessing を検証
- [ ] enable / disable
- [ ] effect state を画面に表示
- [ ] Normal AM の仮プリセット
- [ ] logcat diagnostics

実機確認:

- [ ] YouTube
- [ ] 音楽アプリ
- [ ] Chrome
- [ ] 本体スピーカー
- [ ] Bluetooth

判断:

### Green

主要ケースで global effect が成立する。

→ Phase 2 へ。

### Yellow

一部条件のみ成立する。

→ 対応条件を明文化し、複数端末で再検証する。

### Red

他アプリ音声への効果が実用上成立しない。

→ Phase 1B の AudioPlaybackCapture PoC へ。

## Phase 1B: AudioPlaybackCapture PoC

Phase 1 が Red の場合だけ実施します。

- [ ] MediaProjection permission flow
- [ ] AudioPlaybackCaptureConfiguration
- [ ] AudioRecord
- [ ] PCM passthrough
- [ ] custom band-pass DSP
- [ ] AudioTrack playback
- [ ] latency 測定
- [ ] 元音声との二重再生確認
- [ ] capture 不可アプリの挙動確認

採用条件:

- 元音声との競合を含め、日常利用できる UX が成立すること
- global AudioEffect より明確な価値があること

成立しなければ、通常 APK の制約としてプロジェクト仕様を見直します。root 化へ自動的には進みません。

## Phase 2: MVP

Phase 1 で採用する audio backend が決定した後に進みます。

- [ ] RAZIO ON / OFF
- [ ] Normal AM preset
- [ ] effect availability 表示
- [ ] 設定保存
- [ ] lifecycle 対応
- [ ] route change 対応
- [ ] エラーハンドリング
- [ ] 最小 Compose UI
- [ ] 実機 regression 手順

## Phase 3: Sound design

基本動作が安定した後に音の個性を作ります。

候補:

- [ ] Narrow AM
- [ ] Vintage speaker
- [ ] Weak signal
- [ ] Distant radio
- [ ] Saturation
- [ ] Hiss
- [ ] Crackle
- [ ] Fading
- [ ] Mono 感の強化

AudioEffect API だけで困難な項目は backend の制約を確認してから実装します。

## Phase 4: Product UI

- [ ] レトロラジオ風デザイン
- [ ] tuning dial の表現
- [ ] signal meter
- [ ] preset UI
- [ ] dark / light 方針
- [ ] icon / branding

UI が audio backend の仕様を隠さないようにします。

## Phase 5: Automation

- [ ] GitHub Actions
- [ ] unit test
- [ ] lint
- [ ] assembleDebug
- [ ] artifact APK
- [ ] AI エージェント用実装ループ改善

ADB 実機試験はローカル環境を基本とし、必要になった場合に device farm を検討します。

## 優先順位の原則

```text
音声経路の成立性
    > 安定性
    > テスト可能性
    > AM らしさ
    > UI の作り込み
```

RAZIO では、見た目が完成していても他アプリ音声に効果が掛からなければプロダクトのコア要件を満たしません。
