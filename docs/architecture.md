# Architecture

## 概要

RAZIO は Android の AudioEffect API を第一候補として、他アプリを含むメディア音声へ AM ラジオ風の音質変化を与えることを目指します。

## 初期アーキテクチャ

```text
┌──────────────────────────────┐
│ Other apps                   │
│ YouTube / Music / Games etc. │
└──────────────┬───────────────┘
               │
               ▼
        Android audio mix
               │
               ▼
      Global AudioEffect
      session = 0 (PoC)
        ├─ Equalizer
        ├─ DynamicsProcessing
        └─ Limiter
               │
               ▼
       Speaker / BT / USB
```

アプリ本体は以下に分離します。

```text
app/
├─ ui/
│  ├─ screen/
│  └─ component/
├─ audio/
│  ├─ GlobalAudioEffectController
│  ├─ AudioEffectUiState
│  ├─ preset/
│  └─ AudioEffectLog
├─ domain/
│  └─ model/
├─ RazioApp
└─ MainActivity
```

PoC では `GlobalAudioEffectController` を `Application` に保持する。Foreground Service はまだ入れない。プロセスが死ぬと session 0 の effect も消える。

## AudioEffectController

責務:

- AudioEffect の生成
- 対応可否の判定
- enable / disable
- プリセット適用
- 例外を状態へ変換
- release

Equalizer と DynamicsProcessing は独立に生成し、片方の失敗でももう片方を使えるようにする。session `0` への insert effect は deprecated なので、失敗を隠さない。

`MODIFY_AUDIO_SETTINGS` は manifest で宣言する。runtime permission ではない。

UI から Android API を直接呼ばないようにします。

## 状態モデル

最低限、以下を区別します。

```text
Idle
Initializing
Active
Disabled
Unsupported
Error
```

`Unsupported` と `Error` は別に扱います。

- Unsupported: 端末 / OS / effect 実装として利用不能
- Error: 本来利用できる可能性があるが初期化等に失敗

## AM プリセット

初期 PoC では「正確な AM 放送規格」ではなく聴感を優先します。

概念的な信号処理:

```text
Input
  ↓
Low-frequency attenuation
  ↓
High-frequency attenuation
  ↓
Mid emphasis
  ↓
Compression
  ↓
Limiter
  ↓
Output
```

目安:

- 250 Hz 以下を強く落とす（初期値 -15 dB。端末 EQ の min で clamp）
- 1.8 kHz 以上を強く落とす（初期値 -15 dB。こもった AM 寄り）
- 1 kHz 付近を持ち上げる（初期値 +6 dB）
- ダイナミックレンジを狭くする（MBC ratio 10:1、threshold -24 dB）

実際の Equalizer band は端末の実装から取得して、固定 band 数を仮定しない設計にします。

2026-08-29: Pixel 10 Pro / Android 17 で YouTube・音楽アプリ・Chrome がスピーカーと Bluetooth に乗った。この端末では Global AudioEffect（session 0）を MVP とする。詳細は `docs/audio-research.md`。

## Phase 2 の代替アーキテクチャ

Global AudioEffect が成立しない場合だけ AudioPlaybackCapture を検討します。

```text
Other app playback
        │
        ├──────────────→ original output
        │
        ▼
AudioPlaybackCapture
        │
        ▼
     AudioRecord
        │
        ▼
    Custom DSP
        │
        ▼
     AudioTrack
```

この方式には以下の問題があります。

- MediaProjection の許可が必要
- capture policy により取得できないアプリがある
- 通常アプリでは元音声の抑制が難しい
- 遅延が増える
- 二重再生の可能性がある

したがって、PoC で明確な利点が確認できる場合のみ採用します。

## root / privileged app

現時点では対象外です。

以下へ進む場合はプロダクト方針の変更として扱い、ドキュメントを先に更新してください。

- root
- Magisk
- custom ROM
- privileged / platform-signed app
- Audio HAL 改造

## 永続化

MVP では複雑な DB は不要です。

保存候補:

- RAZIO の ON/OFF
- 選択中プリセット
- 設定値

DataStore Preferences または DataStore Proto を使用し、Room は必要性が出るまで導入しません。

## 依存関係方針

Android 標準 / AndroidX を優先します。

外部 DSP ライブラリは、AudioEffect API では実現できない要件が明確になってから検討します。
