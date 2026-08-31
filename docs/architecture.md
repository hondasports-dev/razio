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
        └─ DynamicsProcessing
           ├─ Pre-EQ (flat)
           ├─ MBC
           ├─ Post-EQ
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
│  ├─ SpectrumAnalyzerController
│  ├─ SpectrumMath
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

### 入出力スペクトラムアナライザー（検証用）

音質プリセットが本当に効いているかを見える化するため、処理経路を置き換えない観測用の2本のtapを用意します。

```text
Other app playback ──┬── original Android output ── Speaker / BT / USB
                     │
                     └── AudioPlaybackCapture → AudioRecord → FFT → 入力グラフ（エフェクト前）
                                                     └→ DynamicsProcessing profile estimate → FFT → 出力グラフ（エフェクト後・推定）

（入力キャプチャ不可時のみ）Android output mix ── Visualizer(session 0) → waveform → FFT → 出力fallback
```

- 入力は `AudioPlaybackCapture` で対象アプリの再生音をPCMコピーし、1024点のHann窓付きFFTで10帯域へ集約します。UIではこのフレームを `入力（エフェクト前）` として扱います。解析PCMを `AudioTrack` へ戻さないため、解析開始による二重再生は発生しません
- Android 10以上で入力キャプチャが動いている場合、出力は入力と同じフレームへ現在の `DynamicsProcessing` のPost-EQ / MBC / Limiterパラメータを決定論的に反映し、同じFFTへ通した `出力（エフェクト後・推定）` です。native MBCのエンベロープ、出力段、HALの後処理は公開APIから読めないため、実スピーカー直後の測定値とは扱いません。推定処理に失敗した場合は入力フレームをそのまま出力へ使い、ログへ記録します。推定の有効判定はUIレポートの一時的な `Unsupported` ではなく、保持中のlive `DynamicsProcessing.enabled` を参照し、readback失敗後に同一フレームを誤表示しないようにします
- 入力キャプチャが使えない端末・権限状態では、`Visualizer(0)` のglobal mix waveformをfallback出力として同じFFTへ通します。`SCALING_MODE_AS_PLAYED` とwaveformのunsigned 8-bit中心値128→PCM変換を従来どおり試みますが、これは `post-DSP非保証` と表示します。Visualizerは出力mix tapであり、入力が取れているときの推定出力と同時には採用しません
- Android 10以上では `RECORD_AUDIO` とMediaProjection同意が必要です。Android 17（API 37）では音声取得を要求した同意UIを出し、MediaProjection用FGSの `startForeground` 完了後に `getMediaProjection` を呼びます。入力キャプチャ中は `mediaProjection` 型FGSを保持します
- 解析停止、同意拒否、Projectionの `onStop`、route changeではAudioRecord / Visualizer / Projectionを解放し、既存のDynamicsProcessing用FGS所有権と独立して扱います

これは音声を加工して差し替えるPhase 2代替経路ではありません。capture policy、DRM、usage制限で入力が取れないアプリは `Partial` / `Error` として表示し、元音声の抑制や自前DSP再生には進みません。実機の同意手順と観測結果は `docs/audio-research.md` に記録します。

### Mono感の強化の成立性

`DynamicsProcessing` は `channelCount` 個のチャンネルを持つ同じ構造の処理段（Pre-EQ / MBC / Post-EQ / Limiter）として動き、各チャンネルのパラメータは独立しています。`setAllChannelsTo` も同じ設定を各チャンネルへコピーするだけで、左右を加算して `mono = (L + R) / 2` にするミックス、チャンネルの入れ替え、出力チャンネル数の変換は行いません。公式リファレンスの構成図もチャンネルごとに Input → stages → Output が分離した構造です（[DynamicsProcessing](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)）。

そのため、現行の session `0` global DynamicsProcessingへ `channelCount=1` を渡しても、他アプリのステレオ音声を確実にモノラル化できる根拠にはなりません。端末のeffect実装で生成に失敗するか、片チャンネルだけを処理するだけになる可能性があり、見かけだけの Mono トグルは追加しません。

真のモノラル化には、RAZIOが所有するPCMをDSPで左右混合してから再生する経路が必要です。自前プレイヤーなら `AudioTrack` の前段で実装できますが、他アプリ音声を対象にする場合は `AudioPlaybackCapture` → `AudioRecord` → `AudioTrack` の差し替え経路になります。この経路は MediaProjection と再生側の capture policy / manifest 設定に依存し、加工前の元音声を抑制できない場合は二重再生になります（[AudioPlaybackCaptureConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration.html)）。

2026-08-31に、代替経路の成立条件を確認するための `MonoPlaybackPocController` を一時的なPoCとして実装しました。これは製品backendを置き換えず、`AudioPlaybackCapture` のPCMを `(L + R) / 2` で平均し、同じ値を左右へ複製して stereo `AudioTrack` へ返す実験でした。元アプリの再生を止めるAPIは持たず、Pixel 10 Proで元音声と加工音の二重化が聴感上確認されたため、Monoの製品採用は見送りました。PoCのcontroller・mixer・UI・FGS連携・unit testは後続変更で削除し、現在の製品経路には残していません。実機Evidenceは `docs/audio-research.md` に履歴として保持します。

FGS の `startForeground` 失敗は logcat に出して隠さない。通知許可がなくても effect の enable は進める。

## AudioEffectController

責務:

- AudioEffect の生成
- 対応可否の判定
- enable / disable
- プリセット適用
- 例外を状態へ変換
- release

DynamicsProcessingを1つだけ生成し、Pre-EQ flat / MBC / Post-EQ / Limiterを同じeffect内で適用する。session `0` への insert effect は deprecated なので、生成・enable失敗を隠さず `Unsupported` / `Error` に反映する。

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

## Product UI first pass

音声経路の状態を隠さないまま、画面を端末コンソール風の Ghost Terminal テーマへ寄せます。アプリ全体の `RazioTheme` は端末壁紙の dynamic color を既定では使わず、暖色の light / dark scheme を提供しますが、`RazioHomeScreen` はその上に暗い緑黒の端末パレットを局所適用します。これにより、音声処理の状態を緑・シアンのステータス行で読み取れ、低照度でもカーブと操作部のコントラストを保てます。

`RazioHomeScreen` は、参考画像の1枚の端末コンソールを主画面の骨格にします。ヘッダー、6項目のプリセットレール、端末ログ、周波数カーブ、6つの周波数境界、出力メーター、詳細展開、フッターを同じ縦スクロール面へ並べ、主要部にMaterialカードを積み重ねません。`RetroPanel` はカーブや出力の枠など、画像で枠がある部分だけに使います。プリセットレールは6項目を端末幅へ均等配置し、`Narrow AM` / `Vintage speaker` などを1行で表示します。ヘッダーの電源部は琥珀色の端末トグルとして既存の電源操作経路へ接続します。背景にはUI文字を含まない生成済みのCRTテクスチャ（`app/src/main/res/drawable/ghost_terminal_texture.png`）を重ね、バイナリ雨・走査線・ビネットの質感だけを担わせます。

ランチャーは標準テンプレートのロボット画像を使わず、ベークライト筐体・紙面パネル・琥珀色の同調部品を描いたRAZIO用adaptive iconへ置き換えます。API 21〜25向けには同じベクターをlayer-listでフォールバックし、API 26以降はadaptive iconのmaskに任せます。ホーム画面は Ghost Terminal の緑黒パレットとモノスペース見出しを使い、選択中プリセットの `PresetFrequencyCurve` を常時表示します。検証用スペクトラムとは別に、出力mix tapのRMS／PeakをLED風に示す製品向けsignal meterも表示します。どれもAudioEffectの成立や聴感を単独で保証するものではなく、tapの前後位置は端末依存です。

### プリセット値の試聴調整

実装上、6つの周波数境界は詳細パネルだけでなくカーブ直下の主画面へ常時表示します。詳細パネルへは同調ダイヤルと周波数以外の開発用値だけを展開します。

プリセットレールの下には周波数カーブを常時表示し、その直下に6つの周波数境界を端末コンソール風の細いスライダーで並べます。各境界はスライダーと一定刻みの`−` / `＋`ボタンで調整できます。出力メーターの下には `プリセット初期値に戻す` を置き、選択中プリセットの定義値をすぐ再適用できます。さらに `DETAILS / 開く` を押すと、同調ダイヤルと開発中のゲイン、MBC、入力ゲイン、歪み緩和、Fading深度・周期を展開します。

詳細パネルには従来の`tuning dial`と周波数以外の開発値を配置し、ノイズ、スペクトラム、エンジンの検証パネルもここへ畳み込みます。周波数境界の6本は主画面のカーブ直下へ常時表示し、`PresetFrequencyCurve` と同じモデルを使います。カーブ本体は詳細を閉じても残り、20 Hz〜20 kHzを対数軸にした細分化グリッド上へ、`AudioPresetTuning` の5つのゲイン点を補間したカーブを描き、低域／高域のカット帯と中域帯を網掛け、6つの周波数境界を縦線で示します。スライダーや`−` / `＋`で確定した値に追従するため、どの帯域を削っているかを数値だけでなく視覚的に確認できます。カーブは調整モデルから算出した設計目安で、端末のnative effect出力を直接測定するスペクトラムではありません。

```text
Slider / −＋ buttons
        ↓ AudioPresetTuning.sanitized()
selected preset tuning map (in memory)
        ↓ AudioPresetTuning.toParameters()
80 ms in-place interpolation
        ↓
DynamicsProcessing: Pre-EQ(flat) → MBC → Post-EQ → Limiter
```

`AudioPresetTuning.sanitized()` は周波数を `lowCut < lowTransition < midLow < midHigh < highTransition < highCut` の順に保ち、各値を端末で安全に扱える範囲へ収めます。調整は現在の `DynamicsProcessing` をreleaseせず、既存の約80 ms遷移へ合流させるため、スライダー操作で一瞬effectが外れる経路を作りません。DynamicsProcessing単独経路の歪み緩和マッピングは調整値にも適用され、UIのMBC目標値とnative readback値が異なる場合があります。

値はプリセットごとにプロセス内だけで保持し、DataStoreへは保存しません。アプリ再起動では初期値へ戻ります。`初期値へ戻す` は選択中プリセットの定義値を再適用します。これは音作りを決めるための試聴用UIであり、非インタラクティブな`tuning dial`も帯域位置の確認専用です。製品向けの操作ダイヤルや永続設定とは別扱いです。

## AM プリセット

初期 PoC では「正確な AM 放送規格」ではなく聴感を優先します。

概念的な信号処理:

```text
Input
  ↓ DynamicsProcessing input gain
  ↓ Pre-EQ (flat)
  ↓ MBC / compression
  ↓ Post-EQ (low cut / mid emphasis / high cut)
  ↓ Limiter
  ↓
Output
```

プリセットごとのカーブは次の通りです。音域カーブはDynamicsProcessingのPost-EQへ直接渡し、端末のEqualizer下限には依存しません。

| プリセット | 低域 | 中域 | 高域 | 狙い |
| --- | --- | --- | --- | --- |
| Narrow AM | 300 Hz 以下を -30 dB | 550〜2,200 Hz を +6 dB | 3.0 kHz 以上を -48 dB（2.2 kHzからロールオフ） | 狭いAMラジオ。MBC 10:1 / post +6 dB / makeup +8 dB |
| Vintage speaker | 180 Hz 以下を -30 dB | 450〜2,600 Hz を +5 dB | 4.0 kHz 以上を -48 dB（2.6 kHzからロールオフ） | 70〜80年代ラジカセの紙コーン／箱鳴り風。MBC 10:1 / post +6 dB / makeup +8 dB |
| Weak signal | 380 Hz 以下を -30 dB | 900〜1,100 Hz を +5 dB | 1.35 kHz 以上を -48 dB | 弱い受信。MBC 16:1 / post +8 dB / makeup +10 dB |
| Saturation | 180 Hz 以下を -24 dB | 450〜2,400 Hz を +2 dB | 5.0 kHz 以上を -48 dB（2.4 kHzからロールオフ） | 入力 +10 dBを強いMBCへ押し込む飽和近似。MBC 20:1 / threshold -18 dB / post +4 dB / makeup +4 dB |
| Fading | Narrow AM と同じ | Narrow AM と同じ | Narrow AM と同じ | input gain を約±3 dB、3.2 秒周期でゆっくり変動させる受信揺らぎ |

表のMBC比率・入力ゲインはプリセットの音作り上の目標値です。現行のDynamicsProcessing単独経路では、歪みと音量低下を抑える安全マッピングを通してからnative effectへ渡します。実機readbackの目安は Narrow/Fading `1.2:1`・post `0dB`、Vintage `1.5:1`・post `+2dB`、Weak `4:1`・post `+9dB`、Saturation `8:1`・input `+6dB`・post `0dB` です。

音質カーブはDynamicsProcessingのPost-EQに一度だけ適用します。Pre-EQはflat、Equalizerは生成・適用せず、端末のEqualizer下限による浅い高域カットを避けます。DynamicsProcessingが生成できない端末では `Unsupported` / `Error` を表示し、Equalizerへ戻す暗黙のフォールバックは行いません。
- 非Saturation（Narrow / Vintage / Weak / Fading）は、実機での歪み報告を受けて穏和化したMBCを使います。目安は Narrow/Fading `1.2:1`・post `0dB`、Vintage `1.5:1`・post `+2dB`、Weak `4:1`・post `+9dB`。Post-EQは中域を最大 `+3dB` まで許容します。Saturationは入力を抑えた強いMBCで意図した質感を残します。
- MBC後段にプリセットごとのPost-EQを置き、makeup gainで低域・高域のカットが戻らないようにします。最終ピークはlimiter（-1 dB）で制限します。
- Saturation だけは DynamicsProcessing の input gain を +10 dB にし、強い MBC（20:1）へ入力を押し込みます。Android AudioEffect に汎用 wave-shaper はないため、倍音を含む物理的な飽和とは区別して扱います。
- Fading は独立したノイズ信号を生成せず、DynamicsProcessing の input gain を Handler で約 100 ms ごとに更新します。プリセット切替・OFF・route change・release では更新 Runnable を必ずキャンセルし、effect chain を解放した後に古い更新が走らないようにします。

プリセット変更は既存のDynamicsProcessingをreleaseせず、約80 msの補間でPost-EQ / MBCパラメータを更新します。切替中の再タップは、その時点の補間値を次の遷移の開始値にします。effectが壊れて更新できない場合だけ再生成へ戻します。

DynamicsProcessing は null config の既定値をプリセット適用済みとして扱いません。まず端末の実チャンネル数を probe して RAZIO の Config を作成し、対応する Config を作れない場合は `Failed` / `Unsupported` として表示します。これにより、既定値の未設定 band が残ったまま成功扱いになることを防ぎます。

Equalizerは現行の製品経路から外しました。端末ごとに異なるband数・min/maxへ依存せず、DynamicsProcessingの20 kHzまでのPost-EQ bandを観測対象にします。

生成直後と既存effectの再利用時には、Post-EQが `inUse` / `enabled` であること、9 bandが欠落・追加なく存在すること、全bandが有効であることをreadbackします。9 / 18 / 20 kHz帯のゲインが高域目標（`-48dB`）を保ち、終端cutoffが20 kHz付近に届かない端末は、音が素通りする可能性があるため `Unsupported` / `Error` として扱い、`Ready` や `Active` にはしません。

Hiss / Crackle のような独立ノイズは、現在の global DynamicsProcessingだけでは生成・混合できません。AudioPlaybackCaptureを使う代替案はMediaProjectionの許可、capture policy、元音声との二重再生が発生し得るため、別途成立性を確認するまで保留します。現在は `NoiseOverlayController` が生成したPCMを通常の `AudioTrack` で別経路再生するPoCを実装し、元音声を捕捉・再再生しない構成で実機構造確認とユーザー聴感受入まで完了しています。製品採用はこの独立オーバーレイ方式を基礎にし、VLC側のループ再生や長時間バックグラウンド無音化は別検証として扱います。

### DynamicsProcessing単独化の決定

2026-08-30に、ユーザー判断によりA/B PoCのB案を現行経路として採用しました。`GlobalAudioEffectController` はDynamicsProcessingを1つだけ生成し、Pre-EQ flat → MBC → Post-EQ → Limiterの順で全プリセットを処理します。Equalizerは生成せず、UIには `Not used (backend=dynamics_only)` と表示します。これにより端末Equalizerの浅い下限に縛られず、10 kHz付近を含む高域へ`-48 dB`の目標を渡せます。

DynamicsProcessingの生成・enableに失敗した場合は状態を `Unsupported` / `Error` として表示します。Equalizerを代替経路として再生成しないため、対応端末条件は従来より明確になります。プリセット切替・route change・OFF・releaseでは、単一effectのlifecycleと約80 msの補間を維持します。

2026-08-29: Pixel 10 Pro / Android 17 で YouTube・音楽アプリ・Chrome がスピーカーと Bluetooth に乗った。この端末では Global AudioEffect（session 0）を MVP とする。詳細は `docs/audio-research.md`。

## Phase 2 の代替アーキテクチャ

Global AudioEffect が成立しない場合に、音声を加工して差し替える方式として AudioPlaybackCapture を検討します。上記のスペクトラムアナライザーは成立性・聴感を確認するための観測専用で、元音声を抑制したり加工音を再生したりしません。Monoの差し替え検証は過去に別PoCとして実施しましたが、二重化のため製品採用せず、現在のアプリから削除済みです。

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

#### Mono PoC（削除済み・履歴）

削除前に実施したMono PoCの処理は次の固定順です（履歴）。

```text
MediaProjection consent + projection FGS
        │
        ▼
AudioPlaybackCaptureConfiguration (MEDIA / GAME / UNKNOWN; UID filterなし)
        │
        ▼
AudioRecord (stereo; mono fallback is Partial)
        │
        ▼
PCM mixer: mono = (L + R) / 2, duplicate to L/R
        │
        ▼
AudioTrack (stereo, capture policy = NONE)
```

`AudioTrack`の出力は元アプリと同じsystem mixへ参加するため、元音声が継続して聞こえる二重再生を通常アプリから防げるとは扱いません。`USAGE_MEDIA` / `GAME` / `UNKNOWN` に一致する複数アプリの音が同じcaptureへ混ざる可能性もあり、削除前のPoCにアプリUIDフィルターはありませんでした。PoCの合格は「2ch capture形式・downmix・再生・停止が観測できること」と「元音声を抑制できない条件を明示できること」に限定し、左右独立信号や音質、日常利用に耐える差し替えbackendの採用条件とは分けました。ユーザー聴感でも二重化が確認されたため、このMono差し替えbackendは製品経路へ採用せず、controller・mixer・UI・service連携・unit testを削除しました。Activity task removalでは削除前のMono/Spectrum controllerを停止してcapture資源を解放していましたが、現在はSpectrum controllerだけが残り、global effect ownerの寿命とは別扱いです。

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
- プリセット値の試聴調整は保存しない（プロセス内のみ）

プリセット変更時は既存の session 0 DynamicsProcessing を release せず、Post-EQ / MBC のパラメータを約 80 ms かけて段階更新します。段数や effect が壊れている場合だけ再生成へフォールバックします。Room は必要性が出るまで導入しません。

起動時に保存済みプリセットを復元してから effect を初期化し、保存済み ON なら effect を enable し、FGS も起動する。UI のスイッチは `RazioApp.setPowerOn` 経由、プリセット選択は `RazioApp.setPreset` 経由で effect と prefs を更新する。短時間の連続操作では古い DataStore 書き込みをキャンセルし、最後の選択が後から上書きされないようにする。API 33 以上では ON 時に `POST_NOTIFICATIONS` を要求する。

## 依存関係方針

Android 標準 / AndroidX を優先します。

外部 DSP ライブラリは、AudioEffect API では実現できない要件が明確になってから検討します。
