# RAZIO

RAZIO は、Android 端末で再生される音声を昔の AM ラジオのような音質に変換して楽しむことを目的とした個人向け Android アプリです。

> 現代の音を、昔の電波へ。

## 目的

RAZIO は、YouTube、音楽アプリ、ゲーム、ブラウザなどの再生音を、可能な範囲で端末全体に対して AM ラジオ風へ変換する体験を提供します。

本プロジェクトでは **root 化を前提にしません**。Android の公開 API と通常アプリで利用可能な仕組みを優先し、端末依存の挙動は実機検証で判断します。

## 技術方針

- Kotlin
- Jetpack Compose
- Gradle
- Android AudioEffect API
- Equalizer / DynamicsProcessing
- ADB による実機検証
- Codex / AI エージェントによる実装・テストループ

## 最初に検証すること

最優先の PoC は、audio session `0` に対する global AudioEffect が対象端末で実際に機能するかの確認です。

想定する AM ラジオ風処理:

- Mono 化（可能な範囲）
- 低域を大幅に減衰
- 3〜4 kHz 以上を大幅に減衰
- 1〜2 kHz 付近を強調
- Compression
- Limiter

この方式は Android では deprecated な領域を含み、端末・OS・Audio HAL の実装によって動作が異なる可能性があります。そのため、実装より先に小さな PoC で成立性を検証します。

## 代替案

Global AudioEffect が成立しない場合は `AudioPlaybackCapture` を調査します。ただし、以下の制約があります。

- MediaProjection のユーザー許可が必要
- 再生側アプリの capture policy に依存
- 元音声の抑制が難しく、加工音との二重再生が発生し得る

したがって、現時点では第一選択ではありません。

## 開発環境

詳細は `docs/development.md`。最短は:

```bash
./gradlew test
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

package / applicationId は `dev.hondasports.razio`。

## ドキュメント

詳細は `docs/` 配下を参照してください。

- `docs/product-spec.md`
- `docs/architecture.md`
- `docs/audio-research.md`
- `docs/development.md`
- `docs/testing.md`
- `docs/roadmap.md`

AI エージェント向けルールは `AGENTS.md` に記載します。
