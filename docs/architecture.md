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
│  ├─ NoiseOverlayController
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
| Narrow AM | 300 Hz 以下を -30 dB | 550〜2,200 Hz を +6 dB | 3.0 kHz 以上を -40 dB（2.2 kHzからロールオフ） | 狭いAMラジオ。MBC 10:1 / post +6 dB / makeup +8 dB |
| Vintage speaker | 180 Hz 以下を -30 dB | 450〜2,600 Hz を +5 dB | 4.0 kHz 以上を -40 dB（2.6 kHzからロールオフ） | 70〜80年代ラジカセの紙コーン／箱鳴り風。MBC 10:1 / post +6 dB / makeup +8 dB |
| Weak signal | 380 Hz 以下を -30 dB | 900〜1,100 Hz を +5 dB | 1.35 kHz 以上を -40 dB | 弱い受信。MBC 16:1 / post +8 dB / makeup +10 dB |
| Saturation | 180 Hz 以下を -24 dB | 450〜2,400 Hz を +2 dB | 5.0 kHz 以上を -40 dB（2.4 kHzからロールオフ） | 入力 +10 dBを強いMBCへ押し込む飽和近似。MBC 20:1 / threshold -18 dB / post +4 dB / makeup +4 dB |
| Fading | Narrow AM と同じ | Narrow AM と同じ | Narrow AM と同じ | input gain を約±3 dB、3.2 秒周期でゆっくり変動させる受信揺らぎ |

音質カーブは原則 Equalizer に一度だけ適用します。Equalizer が生成できない端末では、DynamicsProcessing の pre-EQ をフォールバックとして使い、同じカーブを二重適用しません。
- 非Saturation（Narrow / Vintage / Weak / Fading）は、実機での歪み報告を受けて両backendとも穏和化したMBCを使います。Dynamics onlyの目安は Narrow/Fading `1.2:1`・post `0dB`、Vintage `1.5:1`・post `+2dB`、Weak `4:1`・post `+9dB`。Dynamics onlyのPost-EQは中域を最大 `+3dB` まで許容します。SaturationはSplitでは基準値の強いMBC、Dynamics onlyでは入力を抑えた強いMBCを使い、意図した質感を残します。
- MBC 後段にプリセットごとの post / makeup gain を加え、EQ と圧縮による過度な音量低下を抑えます。Dynamics onlyのPost-EQ正ブーストは `+3dB` までに制限し、最終ピークは limiter（-1 dB）で制限します。
- Saturation だけは DynamicsProcessing の input gain を +10 dB にし、強い MBC（20:1）へ入力を押し込みます。Android AudioEffect に汎用 wave-shaper はないため、倍音を含む物理的な飽和とは区別して扱います。
- Fading は独立したノイズ信号を生成せず、DynamicsProcessing の input gain を Handler で約 100 ms ごとに更新します。プリセット切替・OFF・route change・release では更新 Runnable を必ずキャンセルし、effect chain を解放した後に古い更新が走らないようにします。

プリセット変更は既存の Equalizer / DynamicsProcessing を release せず、約 80 ms の補間でパラメータを更新します。切替中の再タップは、その時点の補間値を次の遷移の開始値にします。effect が壊れて更新できない場合だけ、従来通り再生成へ戻します。

DynamicsProcessing は null config の既定値をプリセット適用済みとして扱いません。まず端末の実チャンネル数を probe して RAZIO の Config を作成し、対応する Config を作れない場合は `Failed` / `Unsupported` として表示します。これにより、既定値の未設定 band が残ったまま成功扱いになることを防ぎます。

実際の Equalizer band は端末の実装から取得して、固定 band 数を仮定しない設計にします。

Hiss / Crackle のような独立ノイズは、現在の global AudioEffect（Equalizer / DynamicsProcessing）だけでは生成・混合できません。AudioPlaybackCapture を使う代替案は MediaProjection の許可、capture policy、元音声との二重再生が発生し得るため、別途成立性を確認するまで保留します。現在は `NoiseOverlayController` が生成したPCMを通常の `AudioTrack` で別経路再生するPoCを実装し、元音声を捕捉・再再生しない構成で実機構造確認とユーザー聴感受入まで完了しています。製品採用はこの独立オーバーレイ方式を基礎にし、VLC側のループ再生や長時間バックグラウンド無音化は別検証として扱います。

### DynamicsProcessing 単体化の A/B PoC

現行MVPは、実機で成立を確認できた `Equalizer + DynamicsProcessing` を既定経路として維持します。DynamicsProcessing には Pre-EQ / MBC / Post-EQ / Limiter があるため、EQも含めて1つの effect に収められる可能性があります。ただし、DynamicsProcessing の端末実装差と、現在の Equalizer 経路で得られた実機受入を同時に失わないよう、いきなり既定経路を置き換えません。

PoCは、今回の全プリセット両端カット再調整をユーザー聴感で受入れた後、Hiss / Crackle の AudioTrack オーバーレイ実装へ着手する前に、1回の実機A/B検証として実施します。

比較する経路:

- A（現行）: Equalizer が音域カーブ、DynamicsProcessing は Pre-EQ flat + MBC + Limiter
- B（候補）: Equalizerを生成せず、DynamicsProcessing の MBC + Post-EQ + Limiter で全カーブを処理。Post-EQをMBCの後段に置き、makeup gainで低域・高域のカットが戻らないようにする。BだけMBCを穏やかにし、Narrow AM/Fading `1.2:1`・post `0dB`、Vintage speaker `1.5:1`・post `+2dB`、Weak signal `4:1`・post `+9dB`、Saturation `8:1`・input `+6dB`・post `0dB`（共通 attack/release `20/230ms`、knee `12dB`）として過度な圧縮歪みを避ける。Post-EQの正のブーストは `+3dB` を上限とする

Bが端末・出力先で安定し、Aより低域/高域のカット量と声域の明瞭度が良く、音量差・クリック・歪みが許容範囲なら、対応端末だけBを優先する候補にします。Bが失敗または聴感で劣る場合はAを維持し、Equalizerが使えない端末だけ既存のDynamicsProcessing Pre-EQフォールバックを使います。PoC実装では画面の「処理方式」から `Split` / `Dynamics only` を選べます。切替時は二つのchainを同時に残さないよう既存effectをreleaseして再生成し、ON状態だけ復元します。設定は永続化せず、起動時は `Split` に戻します。詳細な実施条件は `docs/audio-research.md`、チェック手順は `docs/testing.md` に記録します。

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
