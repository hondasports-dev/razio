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
│  ├─ RazioAudioService
│  ├─ AudioEffectUiState
│  ├─ preset/
│  └─ AudioEffectLog
├─ domain/
│  └─ model/
├─ RazioApp
└─ MainActivity
```

`GlobalAudioEffectController` は `Application` に保持する。ON のあいだ `RazioAudioService`（foregroundServiceType=`specialUse`）を起動し、プロセスが殺されにくくする。FGS は自前メディア再生ではないので `mediaPlayback` は使わない。プロセスが死んだ場合は ON なら次回起動時に effect と FGS を付け直す。出力先の増減では preset を付け直し、effect が死んでいれば作り直す。

### Hiss / Crackle の独立ノイズオーバーレイ PoC

Hiss / Crackle を試す場合は、元音声を `AudioPlaybackCapture` で取り込んで再再生するのではなく、RAZIO が生成したノイズだけを `AudioTrack` へ書き込み、Android の system mix に同時参加させる別経路を検証します。

```text
Other app playback ───────────────┐
                                  ├─ Android system mix ── Speaker / BT / USB
RAZIO generated noise → AudioTrack ┘
```

この方式は元音声を二重に再生しない一方、ノイズは元音声と独立して鳴るため、信号レベル追従や元音声の置換はできません。PoCでは `USAGE_MEDIA` / `CONTENT_TYPE_UNKNOWN` を使い、アクセシビリティ用途を偽装しません。AudioFocusを要求しない同時再生が端末で維持されるか、Android 17のbackground audio hardeningで無音化されないかを実機で確認してから採用判断します。詳細な試行条件とEvidenceは `docs/audio-research.md` に残します。

FGS の `startForeground` 失敗は logcat に出して隠さない。通知許可がなくても effect の enable は進める。

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

プリセットごとのカーブは次の通りです。すべて端末 EQ の min / max で clamp します。

| プリセット | 低域 | 中域 | 高域 | 狙い |
| --- | --- | --- | --- | --- |
| Narrow AM | 250 Hz 以下を -18 dB | 500〜2,400 Hz を +6 dB | 3.4 kHz 以上を -24 dB（2.4 kHzからロールオフ） | 狭いAMラジオ。MBC 10:1 / post +6 dB / makeup +8 dB |
| Vintage speaker | 120 Hz 以下を -18 dB | 350〜3,000 Hz を +4 dB | 4.8 kHz 以上を -20 dB（3 kHzからロールオフ） | 70〜80年代ラジカセの紙コーン／箱鳴り風。MBC 10:1 / post +6 dB / makeup +8 dB |
| Weak signal | 320 Hz 以下を -18 dB | 850〜1,150 Hz を +4 dB | 1.45 kHz 以上を -24 dB | 弱い受信。MBC 16:1 / post +8 dB / makeup +10 dB |
| Saturation | 100 Hz 以下を -8 dB | 300〜3,000 Hz を +2 dB | 7 kHz 以上を -8 dB | 入力 +10 dBを強いMBCへ押し込む飽和近似。MBC 20:1 / threshold -18 dB / post +4 dB / makeup +4 dB |
| Fading | Narrow AM と同じ | Narrow AM と同じ | Narrow AM と同じ | input gain を約±3 dB、3.2 秒周期でゆっくり変動させる受信揺らぎ |

音質カーブは原則 Equalizer に一度だけ適用します。Equalizer が生成できない端末では、DynamicsProcessing の pre-EQ をフォールバックとして使い、同じカーブを二重適用しません。
- ダイナミックレンジを狭くする（Narrow / Vintage は MBC ratio 10:1、threshold -24 dB。Weak signal は 16:1、threshold -30 dB）
- MBC 後段にプリセットごとの post / makeup gain を加え、EQ と圧縮による過度な音量低下を抑えます。最終ピークは limiter（-1 dB）で制限します。
- Saturation だけは DynamicsProcessing の input gain を +10 dB にし、強い MBC（20:1）へ入力を押し込みます。Android AudioEffect に汎用 wave-shaper はないため、倍音を含む物理的な飽和とは区別して扱います。
- Fading は独立したノイズ信号を生成せず、DynamicsProcessing の input gain を Handler で約 100 ms ごとに更新します。プリセット切替・OFF・route change・release では更新 Runnable を必ずキャンセルし、effect chain を解放した後に古い更新が走らないようにします。

プリセット変更は既存の Equalizer / DynamicsProcessing を release せず、約 80 ms の補間でパラメータを更新します。切替中の再タップは、その時点の補間値を次の遷移の開始値にします。effect が壊れて更新できない場合だけ、従来通り再生成へ戻します。

DynamicsProcessing は null config の既定値をプリセット適用済みとして扱いません。まず端末の実チャンネル数を probe して RAZIO の Config を作成し、対応する Config を作れない場合は `Failed` / `Unsupported` として表示します。これにより、既定値の未設定 band が残ったまま成功扱いになることを防ぎます。

実際の Equalizer band は端末の実装から取得して、固定 band 数を仮定しない設計にします。

Hiss / Crackle のような独立ノイズは、現在の global AudioEffect（Equalizer / DynamicsProcessing）だけでは生成・混合できません。AudioPlaybackCapture を使う代替案は MediaProjection の許可、capture policy、元音声との二重再生が発生し得るため、別途成立性を確認するまで保留します。

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

保存:

- RAZIO の ON/OFF（DataStore Preferences）
- 選択中プリセット（DataStore Preferences。未保存時は Narrow AM）

プリセット変更時は既存の session 0 effect を release せず、Equalizer / DynamicsProcessing のパラメータを約 80 ms かけて段階更新します。段数や effect が壊れている場合だけ再生成へフォールバックします。Room は必要性が出るまで導入しません。

起動時に保存済みプリセットを復元してから effect を初期化し、保存済み ON なら effect を enable し、FGS も起動する。UI のスイッチは `RazioApp.setPowerOn` 経由、プリセット選択は `RazioApp.setPreset` 経由で effect と prefs を更新する。短時間の連続操作では古い DataStore 書き込みをキャンセルし、最後の選択が後から上書きされないようにする。API 33 以上では ON 時に `POST_NOTIFICATIONS` を要求する。

## 依存関係方針

Android 標準 / AndroidX を優先します。

外部 DSP ライブラリは、AudioEffect API では実現できない要件が明確になってから検討します。
