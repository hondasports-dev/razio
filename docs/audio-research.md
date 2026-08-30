# Audio Research

## 目的

RAZIO の成立性を、Android の仕様と実機挙動の両面から記録します。

## 重要な前提

### Global AudioEffect（現行はDynamicsProcessing単独）

Android では audio session `0` を global output mix として扱う AudioEffect の利用方法が歴史的に存在します。

ただし insert effect（Equalizer など）を session `0` に付けることは **deprecated**。端末ごとの Audio HAL / effect implementation に依存し、生成できたことと他アプリへ効くことは別問題です。

初期PoC（履歴）では次を独立に試しました。通常のEqualizerは現在の製品経路では生成しません。

- `Equalizer(priority, 0)` と `DynamicsProcessing(priority, 0, config)` を独立に試す（旧Split検証）
- `MODIFY_AUDIO_SETTINGS` を manifest に入れる
- 生成・enable・release の成否を `RAZIO/AudioEffect` タグで出す
- 効果オブジェクトは `Application` 生存期間。ON 中は `RazioAudioService`（specialUse FGS）でプロセスを維持する

そのため、RAZIO では「API が存在する = 必ず全アプリに効く」と判断しません。

## PoC で確認すること

### 1. Effect の生成

- `DynamicsProcessing(0, ...)` が生成できるか
- Post-EQの `inUse` / `enabled`、9 band、9 / 18 / 20 kHzの有効状態と`-48dB` readbackを確認できるか
- `enabled = true` が成功するか
- 例外 / error code

Equalizerの生成可否は旧Split PoCの履歴としてのみ記録し、現行経路の合否条件には含めません。

### 2. 実際の効果

以下の再生中に ON / OFF を比較します。

- YouTube
- 音楽ストリーミングアプリ
- Chrome の動画 / 音声
- ローカルメディアプレイヤー
- ゲーム（必要に応じて）

### 3. 出力先

最低限、以下を分けて記録します。

- 本体スピーカー
- 有線イヤホン（対象端末が対応する場合）
- Bluetooth
- USB Audio

同じ端末でも output device によって effect chain が異なる可能性があります。

### 4. ライフサイクル

- アプリ起動直後
- RAZIO をバックグラウンドへ移動
- 対象アプリ切り替え
- Bluetooth 接続 / 切断
- 画面 OFF / ON
- Audio route 変更
- RAZIO 終了後

## AudioPlaybackCapture

Global AudioEffect が成立しない場合の代替候補です。

利点:

- PCM をアプリ側で取得できれば自由な DSP が可能
- EQ だけでなく noise / crackle / fading などを実装できる

制約:

- Android 10 以降が基本
- MediaProjection の許可が必要
- 再生側の capture policy に依存
- usage による制限がある
- DRM / セキュア音声などは期待しない
- 加工後音声を AudioTrack で再生すると元音声との二重再生が問題になる

このため RAZIO の第一候補にはしません。

ただし、入出力の差を確認する検証用途では、AudioPlaybackCaptureで得たPCMを再生し直さずFFTへ渡すだけの観測tapとして利用できます。この用途は下記の「入出力スペクトラムアナライザー」に限定し、global AudioEffectを置き換えるcustom DSP経路とは分けて扱います。

## AM ラジオらしさの考え方

AM ラジオらしさは AM 変調方式そのものだけで決まりません。

ユーザーが「昔の AM ラジオ」と感じる主な要素:

- 狭い周波数帯域
- 低域の不足
- 高域の不足
- 中域優位
- 小型スピーカーの癖
- 強いコンプレッション
- 軽い歪み
- ノイズ
- フェージング
- モノラル感

MVP は前半の要素を優先し、装飾的なノイズは後から追加します。

## 実機検証ログ

検証ごとに以下のテンプレートを追記します。

```markdown
### YYYY-MM-DD / Device name

- Device:
- Android:
- Build:
- Output:
- Target app:
- Effect:
- session 0 initialization: success / failure
- Audible effect: yes / no / partial
- logcat:
- dumpsys:
- Reproduction steps:
- Conclusion:
```

### 2026-08-29 / device verification BLOCKED

- Device: none attached (`adb devices` empty)
- Android: n/a
- Output: n/a
- Target app: n/a
- Effect: Equalizer + DynamicsProcessing session 0 (code path ready)
- session 0 initialization: not observed on device
- Audible effect: not observed
- logcat: n/a
- dumpsys: n/a
- Reproduction steps: USB debugging 可能な実機を接続してから `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Conclusion: 実装と unit/lint/assembleDebug は通った。system-wide audio の成立判定は実機なしのため **BLOCKED**。PASS にしない。

### 2026-08-29 / Pixel 10 Pro

- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)
- Android: 17 (API 37)
- Build: `CP2A.260805.005`
- Output: **Bluetooth イヤホン**（ユーザー報告。本体スピーカーは未試聴）
- Target app: **YouTube**（後続エントリで確認）。最初の他アプリ試聴時はアプリ名未記録
- Effect: EqualizerBundle + DynamicsProcessing on session `0`
- session 0 initialization: **success**
- Enable / disable: **success**（UI `Active` / `Disabled`、`enabled` actual が追従）
- Audible effect: **yes / weak** on Bluetooth（他アプリへ効果はあるが AM らしさは不足）
- logcat (`RAZIO/AudioEffect`):
  - `equalizer create ok session=0 bands=5 60Hz:-1200mB 230Hz:-1200mB 910Hz:119mB 3600Hz:-1200mB 14000Hz:-1200mB`
  - `dynamics create ok session=0 channels=2 am-config`
  - `equalizer setEnabled requested=true actual=true`
  - `dynamics setEnabled requested=true actual=true`
  - `equalizer setEnabled requested=false actual=false`
  - `dynamics setEnabled requested=false actual=false`
- dumpsys `media.audio_flinger` (ON 時):
  - `2 effects for session 0`
  - EqualizerBundle pid `16982` (`dev.hondasports.razio`) Enabled `y` Suspended `n`
  - DynamicsProcessing pid `16982` Enabled `y` Suspended `n`
- Reproduction steps:
  1. `gradle-run` で `test` / `assembleDebug`
  2. `adb -s 56101FDCH006CX install -r app/build/outputs/apk/debug/app-debug.apk`
  3. `adb shell am start -n dev.hondasports.razio/.MainActivity`
  4. 起動直後は Disabled。スイッチ ON で Active
  5. RAZIO を終了せずバックグラウンドに残し、Bluetooth イヤホンで他アプリの ON/OFF を聴く → 効果あり、弱い
- Conclusion: Pixel 10 Pro の **Bluetooth 出力**で session `0` が他アプリ音声に乗る。仮 AM カーブでは弱い。BT とスピーカーは effect chain が違うことがあるので、スピーカーは別試聴。経路は採用候補。現プリセットを UI 待ちにせず先に強くする。

### 2026-08-29 / Pixel 10 Pro / stronger AM preset

- Device: Pixel 10 Pro (`blazer`)
- Android: 17
- Output: ユーザー再聴待ち（前回は Bluetooth）
- Effect: 同じ session 0。EQ ±15 dB 上限、中域 +6 dB、MBC 10:1 / -24 dB / post +4 dB
- session 0 initialization: **success**
- Enable / disable: **success** (`Active`)
- Audible effect: **yes / strong**（ユーザー。Bluetooth。まだ少し明るい）
- logcat:
  - `equalizer create ok session=0 bands=5 60Hz:-1500mB 230Hz:-1500mB 910Hz:347mB 3600Hz:-1500mB 14000Hz:-1500mB`
  - `dynamics create ok session=0 channels=2 am-config`
  - `setEnabled requested=true actual=true` (equalizer / dynamics)
- Reproduction steps: 再インストール後、RAZIO をバックグラウンドに残して Bluetooth で ON/OFF
- Conclusion: 強さは足りた。ユーザーはもっとこもった音を要求。

### 2026-08-29 / Pixel 10 Pro / darker AM high-cut

- Device: Pixel 10 Pro (`blazer`)
- Android: 17
- Output: **Bluetooth イヤホン**
- Target app: **YouTube**
- Effect: ピークを 1.0–1.1 kHz に狭め、1.8 kHz 以上を -15 dB。5 band EQ の表示はほぼ同じ（910 Hz は +3.5 dB のまま）。こもりの本体は DynamicsProcessing preEQ
- session 0 initialization: **success**
- Enable: **success**
- Audible effect: **yes**（ユーザー。YouTube + Bluetooth）
- logcat: `equalizer create ok session=0 bands=5 60Hz:-1500mB 230Hz:-1500mB 910Hz:347mB 3600Hz:-1500mB 14000Hz:-1500mB` / `setEnabled actual=true`
- Conclusion: YouTube の他アプリ再生に session 0 が乗ることを確認。PoC の「主要メディアアプリ 1 つ以上」は満たした。

### 2026-08-29 / Pixel 10 Pro / remaining Phase 1 matrix

- Device: Pixel 10 Pro (`blazer`)
- Android: 17
- Output: **本体スピーカー** と **Bluetooth イヤホン**
- Target app: **YouTube** / **音楽アプリ**（名前未記録） / **Chrome**
- Effect: session 0 Equalizer + DynamicsProcessing（暗い AM カーブ）
- Audible effect: **yes**（ユーザー。4 アプリ系統 × スピーカー / BT）
- Conclusion: この端末では主要アプリと主要出力先で global AudioEffect が成立。Phase 1 を **Green** とし、AudioPlaybackCapture へは進まない。他端末は未検証。

### 2026-08-29 / Pixel 10 Pro / specialUse FGS keep-alive

- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)
- Android: 17
- Build: `CP2A.260805.005`
- Output: 既存経路（スピーカー / Bluetooth）。今回は寿命確認
- Target app: 他アプリ再生（ユーザー聴感）
- Effect: session 0 Equalizer + DynamicsProcessing。ON 中は `RazioAudioService`（`foregroundServiceType=specialUse`）
- session 0 initialization: **success**
- Enable: **success**（force-stop 後の再起動でも `setEnabled actual=true`）
- FGS: **success**
  - logcat: `fgs started`
  - `dumpsys activity services`: `RazioAudioService` `isForeground=true` `types=0x40000000`（SPECIAL_USE）
  - ユーザー: ステータスバー通知あり
  - OFF でサービス消滅、UI `Disabled`
- Background keep-alive: **yes**（ユーザー。ホームへ送って放置しても effect が残る）
- Reproduction steps:
  1. RAZIO を ON
  2. 通知が出ることを確認
  3. Home へ送って他アプリを再生し、effect が残るか聴く
- Conclusion: この端末では FGS によるバックグラウンド維持が成立。プロセス強制終了までは保証しない。

### 2026-08-29 / Pixel 10 Pro / screen off soak

- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)
- Android: 17
- Build: `CP2A.260805.005`
- Output: 既存経路。今回は画面 OFF の寿命確認
- Target app: 他アプリ再生（ユーザー聴感。soak 直後の dumpsys 時点では `state:started` なし）
- Effect: session 0 Equalizer + DynamicsProcessing + `RazioAudioService`
- Screen: `KEYCODE_SLEEP` のあと約 90 秒。`dumpsys power` `mWakefulness=Dozing`
- FGS: **success**（同一 `ServiceRecord`、`isForeground=true`、pid `27462` のまま）
- session 0 after soak: **success**
  - `2 effects for session 0`
  - EqualizerBundle Enabled `y` Suspended `n`
  - DynamicsProcessing Enabled `y` Suspended `n`
  - client pid `27462`
- Audible effect: **yes**（ユーザー。画面 OFF のまま再生しても AM が残る）
- Reproduction steps:
  1. RAZIO を ON
  2. `adb shell input keyevent KEYCODE_SLEEP`
  3. 約 90 秒待つ
  4. `dumpsys activity services` / `dumpsys media.audio_flinger`
  5. 画面 OFF のまま他アプリを再生して聴く
- Conclusion: この端末では画面 OFF でも FGS・session 0 effect・聴感が残る。

### 2026-08-29 / Pixel 10 Pro / Bluetooth route reconnect

- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)
- Android: 17
- Build: `CP2A.260805.005`
- Output: **Pixel Buds Pro 2**（初回確認）/ **SoundCore 2**（端ケース修正後の再確認、Bluetooth A2DP）
- Target app: **Spotify**（route 切断前に再生。切断時は OS が一時停止）
- RAZIO: ON。UI `Active`、FGS は同じ pid `27462`
- Disconnect: `cmd bluetooth_manager disable` 後 `bluetooth_on=0`、`BLE_ON`、接続台数 0
- Reconnect: `cmd bluetooth_manager enable` 後 `bluetooth_on=1`、A2DP `STATE_CONNECTED`、接続台数 1
- Route callback / reapply logcat (`RAZIO/AudioEffect`):
  - `audio devices removed count=1`
  - `route change wantOn=true`
  - `equalizer setEnabled requested=true actual=true`
  - `dynamics setEnabled requested=true actual=true`
  - `audio devices added count=2` / `audio devices added count=1`
  - 各追加イベントでも `route change wantOn=true` と EQ / Dynamics の `actual=true`
- session 0 after reconnect: **success**（`2 effects for session 0`、EqualizerBundle + DynamicsProcessing、Enabled `y`、client pid `27462`）
- Audible effect: Bluetooth 出力の同じ AM カーブは直前のユーザー試聴で **yes**。今回の切断直後は Spotify が一時停止したため、再接続後の聴感は同じ状態でユーザー確認可能
- Reproduction steps:
  1. RAZIO を ON、Bluetooth A2DP 接続、他アプリ音声を再生
  2. Bluetooth adapter を OFF にして `bluetooth_on=0` / 接続台数 0 を確認
  3. `audio devices removed` と `route change`、`actual=true` を確認
  4. Bluetooth adapter を ON にして A2DP が再接続するまで待つ
  5. `audio devices added` と `route change`、`actual=true`、session 0 の 2 effects を確認
- Conclusion: この端末では Bluetooth route の切断・再接続で callback が発火し、session 0 effect が ON のまま再適用される。イヤホン再接続後も FGS と effect chain は維持された。

### 2026-08-29 / Pixel 10 Pro / Narrow AM listen

- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)
- Android: 17
- Build: `CP2A.260805.005`
- Output: **Pixel Buds Pro 2**（Bluetooth A2DP）
- Target app: **Spotify**
- Preset: Narrow AM（300 Hz 以下 / 1.6 kHz 以上を -15 dB、950〜1,050 Hz を +6 dB）
- session 0: Equalizer + DynamicsProcessing、UI `Active`
- UI detail: 5-band EQ の 910 Hz は `470mB`。既存の暗い AM カーブより狭いピークを適用
- Audible effect: **yes**（ユーザー確認: 「前よりこもってるからOK」）
- Conclusion: Pixel 10 Pro + Bluetooth では Narrow AM の狭帯域化を受け入れ。ノイズ / crackle / fading は未実装のまま次の候補へ。

### 2026-08-29 / Pixel 10 Pro / Vintage speaker listen

- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)
- Android: 17
- Build: `CP2A.260805.005`
- Output: **Pixel Buds Pro 2**（Bluetooth A2DP）
- Target app: **Spotify**
- Preset: Vintage speaker（220 Hz 以下を -12 dB、700〜1,350 Hz を +4 dB、2.8 kHz 以上を -12 dB）
- session 0: Equalizer + DynamicsProcessing、UI `Active`
- UI detail: 5-band EQ は `60Hz:-1200mB 230Hz:-1166mB 910Hz:400mB 3600Hz:-1200mB 14000Hz:-1200mB`
- Selection persistence: force-stop → 起動後も Vintage speaker / `Active` を復元
- Audible effect: **yes**（実機試聴後、ユーザーがコミットを指示）
- Conclusion: Narrow AMを既定として残したまま、Vintage speakerを切り替えて比較できる状態を受け入れ。ノイズ / crackle / fading は未実装のまま次の候補へ。

### 2026-08-29 / Pixel 10 Pro / smooth preset transition and gain compensation

- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)
- Android: 17 / build `CP2A.260805.005`
- Output: **Pixel Buds Pro 2**（初回確認）/ **SoundCore 2**（端ケース修正後の再確認、Bluetooth A2DP）
- Target app: **Spotify**（`PlaybackState=PLAYING`）
- Effect: session `0` の Equalizer + DynamicsProcessing を release せず、約 80 ms の補間で Narrow AM / Vintage speaker / Weak signal を更新。Equalizer がある場合は DynamicsProcessing の pre-EQ を flat にして EQ の二重適用を避ける
- Gain: Weak signal の UI detail は `preEq=flat mbcPost=8.0dB`。MBC 後段の makeup gain で EQ / 圧縮による過度な音量低下を補正し、limiter は -1 dB
- session 0: rapid tap 直後と遷移完了後に `2 effects for session 0`（EqualizerBundle + DynamicsProcessing）、UI は `Active` を維持
- Selection persistence: force-stop → 起動後も Weak signal / `Active` / `RazioAudioService` foreground を復元
- Audible effect: **新しい補間 / makeup gain の聴感はユーザー確認待ち**
- Conclusion: 技術的には effect chain を保持したまま切り替えられることを確認。音量差・クリップ・ポンピングは Pixel Buds でユーザー試聴後に受け入れ判定する。

### 2026-08-29 / retro radio and boombox characteristic retune

- Narrow AM: AM 放送の実用帯域を目安に 100 Hz 以下を -12 dB、300 Hz〜3 kHz を +6 dB、5 kHz 以上を -12 dB（3 kHz からロールオフ）
- Vintage speaker: 100 Hz 以下を -12 dB、300 Hz〜3 kHz を +4 dB、10 kHz 以上を -10 dB（3 kHz からロールオフ）
- Weak signal: 狭帯域・強圧縮の既存カーブを維持
- Noise / crackle / physical speaker distortion は、global AudioEffect だけで他アプリ音声へ安全に混ぜる方式が未成立のため今回の変更対象外。MBC / limiter と中域カーブで再現できる範囲に限定
- Recheck: Pixel 10 Pro / Android 17 / **SoundCore 2**（Bluetooth A2DP）で Spotify `PlaybackState=PLAYING` 中に Narrow AM の UI detail が `60Hz:-1200mB 230Hz:-30mB 910Hz:600mB 3600Hz:60mB 14000Hz:-1200mB`、Vintage speaker が `60Hz:-1200mB 230Hz:-160mB 910Hz:400mB 3600Hz:280mB 14000Hz:-1000mB` となり、UI は `Active`
- 連続切替直後も session `0` の `EqualizerBundle` + `DynamicsProcessing` の 2 effects を維持。該当端末でクラッシュ該当ログなし
- Audible effect: **上記再調整後はユーザー確認待ち**

### 2026-08-29 / darker extremes and gain compensation

- User feedback: first wider pass still sounded too bright / bass-heavy and quiet overall
- Narrow AM: 150 Hz 以下 / 4.5 kHz 以上を -18 dB、350 Hz〜2.8 kHz を +6 dB。MBC post +6 dB + makeup +8 dB（effective +14 dB）
- Vintage speaker: 120 Hz 以下 / 8 kHz 以上を -18 dB、350 Hz〜3 kHz を +4 dB。MBC post +6 dB + makeup +8 dB（effective +14 dB）
- Weak signal: 両端を -18 dB、MBC post +4 dB + makeup +8 dB（effective +12 dB）へ変更
- Recheck: Pixel 10 Pro / Android 17 / SoundCore 2 Bluetooth A2DP / Spotify `PLAYING` で、Narrow AM は実装上 `60Hz:-1500mB 230Hz:-839mB 910Hz:600mB 3600Hz:-529mB 14000Hz:-1500mB`、DynamicsProcessing は `preEq=flat mbcPost=14.0dB`。UI は `Active`、session `0` の2 effectsを維持し、force-stop後も Narrow AM / `Active` を復元
- Audible effect: **再調整後の音量・低高域バランスはユーザー確認待ち**

### 2026-08-29 / preset separation and Weak signal gain

- User feedback: Narrow AM と Vintage speaker の差が分かりにくく、Weak signal が小さく、高域をさらに抑えたい
- Narrow AM: 250 Hz 以下を -18 dB、500〜2.4 kHz を +6 dB、3.4 kHz 以上を -24 dB。狭い声域寄りのラジオ特性
- Vintage speaker: 120 Hz 以下を -18 dB、350〜3 kHz を +4 dB、4.8 kHz 以上を -20 dB。Narrow より広いが高域は丸める特性
- Weak signal: 中域を +4 dB、MBC post +8 dB + makeup +10 dB（effective +18 dB）、高域目標 -24 dB
- 実機: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、SoundCore 2（Bluetooth A2DP）、Spotify `PlaybackState=PLAYING`
- UI detail: Narrow AM は `60Hz:-1500mB 230Hz:-1500mB 910Hz:600mB 3600Hz:-1500mB 14000Hz:-1500mB` / `mbcPost=14.0dB`、Vintage speaker は `60Hz:-1500mB 230Hz:-747mB 910Hz:400mB 3600Hz:-400mB 14000Hz:-1500mB` / `mbcPost=14.0dB`、Weak signal は `60Hz:-1500mB 230Hz:-1500mB 910Hz:400mB 3600Hz:-1500mB 14000Hz:-1500mB` / `mbcPost=18.0dB`
- 連続切替（約20 ms間隔）後も `状態: Active` と session `0` の `EqualizerBundle` + `DynamicsProcessing` の2 effectsを維持。`RazioAudioService` は `isForeground=true`。`dumpsys media_session` は Spotify `PLAYING`、`dumpsys audio` は SoundCore 2 `bt_a2dp`。直近400行の logcat に RAZIO のクラッシュ／AudioEffect例外なし
- force-stop → 起動後も Narrow AM / `Active` / 同じ effect detail を復元
- Audible effect: **ユーザー確認済み（「ええ感じ」）。Narrow/Vintageの差、Weak signalの音量、高域量を受入**

### 2026-08-29 / Distant radio candidate dropped

- User feedback: Narrow AM と Distant radio の差が聴き分けにくく、Distant radio は不要
- Decision: Distant radio はプリセット候補・UIから外し、次の音作りは Saturation へ進む

### 2026-08-29 / Saturation first pass

- Scope: Global AudioEffect の既存 EQ / DynamicsProcessing / limiter を使い、入力ゲインを強い MBC に押し込む飽和近似。Android公開AudioEffectに汎用wave-shaperはないため、倍音を含む物理的なサチュレーションは保証しない
- Saturation: 100 Hz 以下を -8 dB、300〜3 kHz を +2 dB、7 kHz 以上を -8 dB。input gain +10 dB、MBC 20:1 / threshold -18 dB / post +4 dB / makeup +4 dB（effective +8 dB）、limiter -1 dB
- 実機: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、SoundCore 2（Bluetooth A2DP）、Spotify `PlaybackState=PLAYING`
- UI detail: `状態: Active`、Equalizer は `session=0 preset=saturation bands=5 60Hz:-800mB 230Hz:-150mB 910Hz:200mB 3600Hz:50mB 14000Hz:-800mB`、DynamicsProcessing は `channels=2 preset=saturation preEq=flat inputGain=10.0dB mbcPost=8.0dB`
- 4プリセットの連続切替（約20 ms間隔）後も session `0` の `EqualizerBundle` + `DynamicsProcessing` の2 effectsを維持し、`RazioAudioService` は foreground。直近 logcat にアプリのクラッシュ／AudioEffect例外なし
- 実機聴感: **ユーザー確認済み（「おｋ」）。Saturationの押し出しと音量を受入**

### 2026-08-29 / Hiss and Crackle backend constraint

- Hiss / Crackle は、入力音声に存在しないノイズ信号を生成して元音声へ混合する必要がある
- 現行の global AudioEffect は session `0` の既存ミックスへ Equalizer / DynamicsProcessing を挿入するだけで、独立した AudioTrack や wave-shaper を安全に追加する API ではない
- AudioPlaybackCapture なら custom DSP でノイズを生成できる可能性はあるが、MediaProjection のユーザー許可、アプリごとの capture policy、元音声を確実に抑制できないことによる二重再生・遅延が発生し得る
- Decision: 現行 backend では Hiss / Crackle を実装せず保留。AudioPlaybackCapture を再検討する場合は、まず capture 可否・二重再生・遅延を別 PoC として測定する

### 2026-08-29 / Hiss and Crackle AudioTrack overlay PoC plan

- Goal: 他アプリの音声を捕捉・再生し直さず、RAZIO が生成したノイズだけを別 `AudioTrack` で同時再生し、Android の system mix で聴感上重ねられるかを確認する
- Initial playback contract: `AudioAttributes.USAGE_MEDIA` + `CONTENT_TYPE_UNKNOWN`、AudioFocus は要求しない。`USAGE_ASSISTANCE_ACCESSIBILITY` を通常アプリの回避策として使わない
- Background: PoC は RAZIO の画面を表示した状態で開始し、既存の `RazioAudioService` を foreground にしたまま Home / 画面 OFF へ移る。Android 17 の background audio hardening による無音化がないかも確認する
- Target apps: YouTube / Spotify / radiko（再生許可・capture policyの影響を受けないため、まずは同時再生そのものを確認）
- Outputs: Pixel 10 Pro 本体スピーカー / SoundCore 2 Bluetooth A2DP
- ON/OFF: ノイズ開始、RAZIO OFFで即停止、再ONで再開。route切替・アプリを背景へ移した後も停止処理が残らないことを確認する
- Acceptance: (1) 対象アプリがpause/意図しないduckを起こさない、(2) ノイズが聞こえる、(3) start/stopにクリック・残留音がない、(4) 元音声を捕捉して再生しないためsourceの二重再生がない、(5) `AudioHardening` / crash / ANRログがない
- Evidence: `dumpsys media_session`（対象アプリがPLAYING）、`dumpsys audio`（出力route）、`dumpsys activity services dev.hondasports.razio`（FGS）、`dumpsys media.audio_flinger`（AudioTrackの出力）、filtered `logcat`（`AudioHardening|FATAL EXCEPTION|ANR|RAZIO/AudioEffect`）を同じ試行で保存する
- Out of scope: AudioPlaybackCapture、元音声のミュート／差し替え、アプリごとの音量追従、DRM/capture policy回避、Hiss/Crackleの物理モデル再現
- Status: **計画時点の記録**。global AudioEffect のHiss/Crackle非対応という判断は維持し、独立ノイズオーバーレイの実装・実機結果は次の記録へ追記する

### 2026-08-30 / Hiss and Crackle AudioTrack overlay PoC implementation

- Implementation: `NoiseOverlayController` が決定論的なPCMを生成し、RAZIOの画面で明示的にONにしたHiss / Crackleだけを通常の `AudioTrack` へ書き込む。`AudioPlaybackCapture` は使わず、元音声をミュート・捕捉・再再生しない。属性は `USAGE_MEDIA` / `CONTENT_TYPE_UNKNOWN`、AudioFocusは要求しない
- Lifecycle: ノイズはRAZIO power ON中だけ有効。power OFFで両スイッチとAudioTrackを停止し、route changeでは既存writerを停止して新しいtrackを作る。PCM生成は専用 `RazioNoiseOverlay` threadで行い、track release後にwriterが残らないようにする
- Unit / build: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `2874600e19b809682ef98d192f8cbe31`）。最新版APK SHA-256 `678780EA7C35DF1ED3F99ED3A4A30B56B7696669B340FD54BAAE95C10DCDC9AC`
- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、Spotify `PlaybackState=PLAYING`、Pixel Buds Pro 2 Bluetooth A2DP。UI detailは `sampleRate=48000Hz buffer=19200B session=... usage=media content=unknown focus=none`
- Structural evidence: `dumpsys audio`でSpotify（music / stereo）とRAZIO（unknown / mono / 48 kHz）の独立AudioTrackが同時に `started`。RAZIOのpower OFF後はUI `Disabled` / noise `Idle`、FGSとRAZIO trackが消えた。Homeと画面OFF（`Dozing`）ではFGS `types=0x40000000` とtrack `started`を維持した
- Route evidence: Bluetooth切断でRAZIO trackがspeaker `deviceIds:[3]`へ移り、再接続でPixel Buds Pro 2 `deviceIds:[7241]`へ戻った。`noise overlay stopped reason=route_change` と再開ログを確認した。Bluetooth再接続時に既存DynamicsProcessingの `UnsupportedOperationException: AudioEffect: invalid parameter` が1件出たが、既存fallbackによるeffect再初期化後もoverlayはactiveだった
- Stability: Home・画面OFF・route切替の各試行前にlogcatをクリアし、新規の `AudioHardening`、`FATAL EXCEPTION`、`ANR in` は確認されなかった。なお、過去のプロセス更新時にはAndroid 17の `AudioHardening background playback would be muted` 履歴があり、長時間バックグラウンドの無音化リスクはゼロとは断定しない
- Audible acceptance: **ユーザー確認済み（「OK」）**。ホストで生成した10秒の無音WAV（48 kHz / mono）を `/sdcard/Download/razio-silence-10s.wav` へ転送し、VLCで再生した。ループ設定なしの短時間試行でもHiss / Crackleが無音ベースへ重なって聞こえることを確認した。独立AudioTrackが出力へ乗ることは受入済み。VLC側のループ再生と長時間バックグラウンド聴感は今回の範囲外として未検証
- Status: **PoC実装・unit test・build・実機構造確認・聴感受入済み。製品採用としてcommit / pushする**

### 2026-08-29 / Fading first pass

- Scope: Hiss / Crackle のような独立ノイズを追加せず、global AudioEffect の DynamicsProcessing input gain だけを周期変動させる
- Fading: Narrow AM と同じ狭帯域 EQ / MBC（input gain 0 dB）を維持し、input gain を ±3 dB、3.2 秒周期、100 ms tick で更新する
- Lifecycle: プリセット遷移完了後に変調を開始し、別プリセットへの切替・OFF・route change・release で Runnable をキャンセルする。更新失敗時も変調を停止し、既存の effect failure path に任せる
- Unit: `AudioPreset.FADING` の帯域、変調深度、周期を確認済み
- 実機 (2026-08-29): Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、Pixel Buds Pro 2 Bluetooth A2DP、Spotify `PlaybackState=PLAYING`
- 実機UI: `状態: Active`、`preset=fading`、EQ `60Hz:-1500mB 230Hz:-1500mB 910Hz:600mB 3600Hz:-1500mB 14000Hz:-1500mB`、Dynamics `channels=2 preEq=flat inputGain=0.0dB mbcPost=14.0dB fade=3.0dB/3200ms`
- 連続切替: Narrow AM → Vintage speaker → Weak signal → Saturation → Fading を約20 ms間隔で選択後、遷移完了時も session `0` の `EqualizerBundle` + `DynamicsProcessing` の2 effectsと `Active` を維持
- OFF/ON: UI `状態: Disabled`、EQ/Dynamics `actual=false`を確認後、再ONで `状態: Active`、EQ/Dynamics `actual=true`、FGS `isForeground=true` に復帰
- 画面OFF: `mWakefulness=Dozing` の約5秒後も FGS `types=0x40000000` と session `0` の2 effectsを維持
- Logcat: Fading実機試行範囲で `fading input gain update failed`、`AudioHardening`、アプリのcrash/ANRなし（周辺システムログは除外）
- 最新APK: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `62b766a9232d66385c5f52638c981382`）、SHA-256 `DF6D00F3B01D9CCD7C2BC3B69A2A1A9A035265159FC73D18C0B5BA9C1E18255C`
- 変調ログ: `fading modulation started depth=3.0dB period=3200ms tick=100ms` → OFF時 `fading modulation stopped` → 再ON時に同じstartログ。EQ/Dynamicsの `actual=false` / `actual=true` も確認
- Audible effect: **ユーザー確認済み（「OK」）**（数秒周期の揺れ、クリック、急な音切れ、音量差を受入）

### 2026-08-29 / all preset edge-cut retune

- User feedback: **全プリセットで低域と高域をさらにカットしたい**
- Rationale: 端末 Equalizer の最小値に達する帯域ではゲイン値だけ下げても音は変わらないため、低域から中域へ入る境界を上げ、高域のロールオフ開始を下げる。DynamicsProcessing fallback 向けには目標ゲインも深くする
- Narrow AM / Fading: 300 Hz 以下 -24 dB、550〜2.2 kHz +6 dB、3.0 kHz 以上 -30 dB（2.2 kHzからロールオフ）
- Vintage speaker: 180 Hz 以下 -24 dB、450〜2.6 kHz +4 dB、4.0 kHz 以上 -26 dB（2.6 kHzからロールオフ）
- Weak signal: 380 Hz 以下 -24 dB、900〜1.1 kHz +4 dB、1.35 kHz 以上 -30 dB
- Saturation: 180 Hz 以下 -18 dB、450〜2.4 kHz +2 dB、5.0 kHz 以上 -18 dB（2.4 kHzからロールオフ）
- Unit / build: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `3192c91500e06f473428e3f5566596d0`）。APK SHA-256 `289F471B2227897D4C54ACF111BFC9DDDE0D0C5BCC9A11174FF7C03FB0E5F54C`
- 実機 (2026-08-29): Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、SoundCore 2 Bluetooth A2DP、Spotify `PlaybackState=PLAYING`
- 実機UI: 全プリセットで `状態: Active`、session `0` の Equalizer + DynamicsProcessing を維持。Narrow AM は `60Hz:-1500mB 230Hz:-1500mB 910Hz:600mB 3600Hz:-1500mB 14000Hz:-1500mB`、Vintage speaker は `60Hz:-1500mB 230Hz:-1500mB 910Hz:400mB 3600Hz:-1500mB 14000Hz:-1500mB`、Weak signal は `60Hz:-1500mB 230Hz:-1500mB 910Hz:400mB 3600Hz:-1500mB 14000Hz:-1500mB`、Saturation は `60Hz:-1500mB 230Hz:-1429mB 910Hz:200mB 3600Hz:-723mB 14000Hz:-1500mB`、Fading は Narrow AM と同じ端部値に `fade=3.0dB/3200ms`
- 連続切替: 5プリセットを順番に選択後も `Active` と2 effectsを維持。OFFで EQ/Dynamics `actual=false`、再ONで `actual=true` と Fading modulation start/stop ログを確認
- 画面OFF: `mWakefulness=Dozing` の約3秒後も FGS `isForeground=true types=0x40000000` と session `0` の2 effectsを維持
- Audible effect: **ユーザー確認済み（「OK」）**（低域の量、高域の残り方、声の明瞭度、音量差、クリックの有無を受入）

### 2026-08-29 / second edge-cut pass

- User feedback: 歪みは収まったが、全プリセットで低音と高音をさらにカットしたい
- Adjustment: 中域のゲインとDynamics onlyのMBC設定は維持し、各プリセットの端部目標だけを約 `-6dB` 深くした。Narrow AM / Fading は低域 `-30dB`・高域 `-36dB`、Vintage speaker は `-30dB`・`-32dB`、Weak signal は `-30dB`・`-36dB`、Saturation は `-24dB`・`-24dB`
- Split の専用 Equalizer は端末側の下限へ clamp されるため既存の受入済み音量・圧縮を変えず、DynamicsProcessing fallback / Dynamics only では深い目標値を利用する
- Unit / build: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `072fac01ea2f8d798c12426264509477`）。APK SHA-256 `A2FAC47D51DB8AE6716A49F54AE1022995122D2A769725940513926D6AEE95BB`
- 実機構造確認: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、Pixel Buds Pro 2 Bluetooth A2DP、Spotify `PlaybackState=PLAYING` で、`状態: Active` / `Equalizer: Not used` / session `0` の `DynamicsProcessing` 1 effect / FGS `isForeground=true` を確認。`postEqBands` は Narrow AM/Fading `90Hz:-30dB`・`250Hz:-30dB`・`4500Hz:-36dB`・`20000Hz:-36dB`、Vintage speaker `90Hz:-30dB`・`250Hz:-30dB`・`9000Hz:-32dB`・`20000Hz:-32dB`、Weak signal `90Hz:-30dB`・`250Hz:-30dB`・`2500Hz:-36dB`・`20000Hz:-36dB`、Saturation `90Hz:-24dB`・`250Hz:-24dB`・`9000Hz:-24dB`・`20000Hz:-24dB` をnative readbackした。crash / ANR / `AudioHardening` はなし
- 聴感: 歪み収束後の低域・高域の追加カットはユーザー確認待ち

### 2026-08-29 / Dynamics only distortion and level retune

- User feedback: `Narrow AM` と `Fading` が歪む。`Saturation` は高域をさらにカットしたい。`Weak signal` はAMらしいが音量が小さい
- Adjustment: Dynamics only のMBCをさらに穏和化し、Narrow AM / Fading は ratio `1.2:1`・post `0dB`、Vintage speaker は ratio `1.5:1`・post `+2dB`、Weak signal は ratio `4:1`・post `+9dB`、Saturation は ratio `8:1`・input `+6dB`・post `0dB` とした。共通 attack/release `20/230ms`・knee `12dB`。MBC後段Post-EQの正のブーストは `+2dB` で上限を設け、Saturationの高域目標は `-36dB` へ深くした
- Unit / build: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `ac68d98fa5f4f1af7789745ef519b477`）。APK SHA-256 `3D9BB57C4393295F6A3ECB1EE4B62612B44EF4218E8716C2F0A01E46D0B4CA1C`
- 実機構造確認: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、Pixel Buds Pro 2 Bluetooth A2DP、Spotify `PlaybackState=PLAYING`。全プリセットで `状態: Active` / `Equalizer: Not used` / session `0` の `DynamicsProcessing` 1 effect / FGS `isForeground=true`、crash / ANR / `AudioHardening`なしを確認した。native readbackは Narrow AM/Fading `ratio=1.2`・`post=0dB`・端部 `-30/-36dB`、Vintage `ratio=1.5`・`post=+2dB`・端部 `-30/-32dB`、Weak `ratio=4`・`post=+9dB`・端部 `-30/-36dB`、Saturation `ratio=8`・`input=+6dB`・`post=0dB`・高域 `-36dB`。Fadingは `fade=3dB/3200ms` を維持した
- Status: **実装・unit test・build・実機構造確認済み。最終音質はユーザー聴感受入待ち**。実機は `Dynamics only / Narrow AM / Active`、Spotify再生中の状態で保持している

### 2026-08-29 / DynamicsProcessing-only EQ A/B PoC plan

> **Historical / Superseded:** このA/B計画では一時的にEqualizer + DynamicsProcessingを比較した。2026-08-30以降の製品経路はDynamicsProcessing単独で固定している。

- Timing: 今回の全プリセット両端カット再調整をユーザー聴感で受入れた直後、Hiss / Crackle の AudioTrack オーバーレイ実装へ着手する前に実施する。既定経路の置換ではなく、検証用のA/B切替として1回の実機サイクルに限定する
- Goal: EQも含めてDynamicsProcessing単体（MBC + Post-EQ + Limiter）へまとめた場合に、現行のEqualizer + DynamicsProcessingより音質・安定性が改善するかを確認する
- A（現行）: Equalizerが音域カーブ、DynamicsProcessingはPre-EQ flat + MBC + Limiter
- B（候補）: Equalizerを生成せず、DynamicsProcessingのMBC + Post-EQ + Limiterで全プリセットを処理。Pre-EQはflatのままにしてEQカーブを二重適用せず、MBC後のPost-EQで最終的な低域・高域カットを保証する
- Device / output: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、本体スピーカー / SoundCore 2 Bluetooth A2DP、Spotify `PlaybackState=PLAYING`
- Cases: Narrow AM / Vintage speaker / Weak signal / Saturation / Fading、ON/OFF、約80 msのプリセット遷移、route change、Home、画面OFF、force-stop後の復元
- Acceptance: BがAより低域・高域のカットと声域の明瞭度で明確に有利、音量差が許容範囲、クリック・過度な歪み・無音化がなく、両出力先とライフサイクルで安定すること。差が小さいか端末依存の失敗がある場合はAを維持
- Evidence: `dumpsys media.audio_flinger`（effect数と構成）、`dumpsys activity services dev.hondasports.razio`（FGS）、`dumpsys audio`（route）、filtered `logcat`（effect生成/enable、`AudioHardening`、crash/ANR）、A/B各プリセットのUI detailと聴感メモ
- Out of scope: Hiss / Crackle生成、AudioPlaybackCapture、元音声のミュート/差し替え、Pre-EQとPost-EQへの同一カーブの二重適用、既定経路の変更
- Implementation: 画面の「処理方式」から `Split` / `Dynamics only` を切り替えられるようにした。`Split` は Equalizer + DynamicsProcessing（Pre-EQ flat）、`Dynamics only` は Equalizer を生成せず DynamicsProcessing の MBC + Post-EQ + Limiter を使う。Post-EQの帯域は20kHzまで拡張し、4.5kHzより上の音域が素通りしないようにした（44.1/48kHz出力で有効な上限）。切替時は古い effect chain を release して再生成し、ON 中なら再 enable する。選択は PoC 用で永続化せず、次回起動は `Split` に戻す
- Distortion mitigation (initial plan): 当初は `Split` の受入済みMBC値を変更せず、`Dynamics only` だけプリセット形状に応じて圧縮を段階化する方針とした。実機聴感でNarrow AM/Fadingの歪みとWeak signalの音量不足が残ったため、Dynamics onlyを先に再調整し、その後 `Split` でも同じ非Saturation穏和化を適用した。現在値は後段の `non-saturation distortion retune` セクションへ更新した。共通で threshold は `-18dB`、attack/release は `20/230ms`、knee は `12dB` とし、Dynamics onlyのMBC後段Post-EQブーストは `+2dB` までに制限する。UI detailはnative readbackのratio / threshold / attack / release / postを表示する
- Objective device evidence (2026-08-29): Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、SoundCore 2 Bluetooth A2DP、Spotify `PlaybackState=PLAYING` で確認。A (`Split`) は UI `状態: Active`、EQ + DP detail（`backend=split preEq=flat`）、session `0` の2 effects。B (`Dynamics only`) への切替後は UI `Equalizer: Not used (backend=dynamics_only)` / `DynamicsProcessing: enabled ... preEq=flat postEq=curve`、session `0` の1 effect（DynamicsProcessingのみ）、FGS `isForeground=true` を確認。UI detailの `postEqBands` readbackで、Narrow AM は `90Hz:-24dB` / `250Hz:-24dB` / `1500Hz:+6dB` / `4500Hz:-30dB` / `20000Hz:-30dB` を確認した。切替ログに旧EQ/DPのreleaseと新DPの生成・enableを確認し、アプリのcrash/ANR・`AudioHardening` はなし（system `EffectProxy` のrelease時警告は記録対象として残る）。旧B実装ではPre-EQの最終cutoffが4.5kHzで高域が帯域外を通過し、MBC後段makeup gainも低域を戻し得たため、Post-EQへ修正して再聴感確認する
- Unit / build: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `e9f43090d5c99cc531f6d81c5f761468`）。APK SHA-256 `B7DBB40BCDD74BC55150B1F310BD15AE65950CB7EEA299960D123B4633E5E311`
- 再調整後構造再確認 (2026-08-29): Pixel 10 Pro / Android 17 / Pixel Buds Pro 2 Bluetooth A2DP / Spotify `PlaybackState=PLAYING` で、`状態: Active`、`Equalizer: Not used`、`preEq=flat postEq=curve`、`postEqBands` の `90Hz:-24dB` / `250Hz:-24dB` / `4500Hz:-30dB` / `20000Hz:-30dB` を確認。native MBC readbackはNarrow AM/Fadingがratio `2:1`・threshold `-18dB`・attack/release `20/230ms`・post `+3dB`、Vintage speakerがratio `3:1`・post `+4dB`、Weak signalがratio `6.4:1`・post `+6dB`、Saturationがinput `+6dB`・ratio `8:1`・post `0dB`。全プリセットで session `0` の1 effect（DynamicsProcessingのみ）、FGS `isForeground=true`、Spotify再生継続、アプリのcrash/ANRなしを確認した。歪み低減の聴感はユーザー確認待ち
- ユーザー聴感メモ（再調整前）: `Narrow AM` は「結構歪んでる」、`Vintage speaker` は「少し歪んでる」、`Weak signal` は「歪んでない」、`Fading` は「AMと同じぐらい歪んでる」と報告。これを受けてNarrow/Fadingの圧縮・post gainを最小側へ、Vintageを中間へ下げ、Weakは変更せずに聴感差を保つ方針とした。Saturationは従来どおり意図的な強い質感として別評価にする
- Status: **プリセット別MBC再調整・構造実機確認済み・聴感受入待ち**。当時はEqualizer + DynamicsProcessingを既定経路としていたが、後述の2026-08-30判断で廃止した

### 2026-08-29 / non-saturation distortion retune

- User feedback: `Saturation` 以外のプリセットにまだ軽い歪みが残る
- Adjustment: 起動時の `Split` を含め、非Saturation（Narrow AM / Vintage speaker / Weak signal / Fading）のMBCをDynamics onlyと同じ穏和プロファイルへ統一した。native readbackの目安は Narrow/Fading `ratio=1.2`・`post=0dB`、Vintage `ratio=1.5`・`post=+2dB`、Weak `ratio=4`・`post=+9dB`、共通 threshold `-18dB`・attack/release `20/230ms`・knee `12dB`。Dynamics onlyのPost-EQ正ブースト上限は `+2dB`。SaturationはSplit `ratio=20`・input `+10dB`・post `+8dB`、Dynamics only `ratio=8`・input `+6dB`・post `0dB` のまま保持した
- Unit / build: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `0ff2a8730778cdae1cdb1687ee20dac3`）。APK SHA-256 `49EF28AC62970E16282240EE7E76A86A6CE86995D11B8BF01CD6CFD1F17531B5`
- 実機確認: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、Pixel Buds Pro 2 Bluetooth A2DP、Spotify `PlaybackState=PLAYING`。`Split` で5プリセットを切替え、Equalizer + DynamicsProcessingの2 effectsを維持し、非Saturationは上記ratio/post、Saturationは従来の強い値をnative/UI readbackした。`Dynamics only` でも5プリセットを切替え、`Equalizer: Not used`、Post-EQ中域 `+2dB` 以下、session `0` のDynamicsProcessing 1 effect、FGS `isForeground=true` を確認。直近logcatにアプリのcrash/ANR/`AudioHardening`なし、Spotify再生継続を確認した
- Status: **非Saturationの両backend穏和化・Post-EQヘッドルーム調整・自動検証・実機構造確認済み。最終聴感受入待ち**。実機は `Dynamics only / Fading / Active` で確認後、聴感用に `Dynamics only / Narrow AM / Active` へ戻す

### 2026-08-29 / mid emphasis and deeper high cut

- User feedback: 中音をもう少し強調し、高音をさらにカットしたい
- Adjustment: Dynamics onlyのPost-EQ正ブースト上限を `+2dB` から `+3dB` へ変更し、非Saturationの声域を少し前へ出した。Split側はVintage speaker / Weak signalの中域目標を `+4dB` から `+5dB` へ変更（Narrow AM / Fadingは端末EQの上限 `+6dB` を維持）。全プリセットの高域目標を `-40dB` に統一し、Saturationの高域カットも維持・強化した
- Unit / build: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `c37ca4a87ccc623ea4aca984cd933b83`）。APK SHA-256 `E9A13DAEAFEE3651D57A36C3391469E8BAFAE26F637CE3B45CD43D103434954D`
- 実機readback: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)、Pixel Buds Pro 2 Bluetooth A2DP。SplitでNarrow/Fading `ratio=1.2`・post `0dB`、Vintage `ratio=1.5`・post `+2dB`、Weak `ratio=4`・post `+9dB`、Saturation `ratio=20`・input `+10dB`・post `+8dB` を確認し、SplitのVintage/Weak中域は端末EQ readbackで `910Hz:+5dB` になった。Dynamics onlyではNarrow/Fadingの中域 `+3dB`・高域 `-40dB`、Vintage/Weakの中域 `+3dB`・高域 `-40dB`、Saturationの中域 `+2dB`・高域 `-40dB` を確認した。Dynamics onlyのMBCは非SaturationがSplitと同じ穏和値、Saturationは `ratio=8`・input `+6dB`・post `0dB` のままや
- 構造・安定性: Splitはsession `0` の2 effects、Dynamics onlyは1 effect。FGS `isForeground=true`、メディアセッション `PLAYING`、crash / ANR / `AudioHardening`なし。聴感の最終受入はユーザー確認待ち

### 2026-08-30 / Retro radio UI first pass

- Scope: 音声backendを変更せず、画面を暖色のベークライト／紙面系テーマへ変更。電源、処理方式／プリセット、ノイズ、エンジン状態を枠付きパネルへ整理し、プリセット選択を `LazyRow` の横スクロールへ変更した
- Theme: Pixelのsystem dark modeでdark scheme（濃い茶色の筐体・琥珀色の選択状態）、light modeでlight scheme（紙面色・錆色の選択状態）を確認。dynamic colorは既定で無効にし、端末壁紙に色が引っ張られないようにした
- Unit / build: `:app:testDebugUnitTest` / `:app:assembleDebug` PASS（workflow `339236eb848ce186f4c8fcfa78ed0a9d`）。APK SHA-256 `13A0C360AC71D87761C6E09145BDC3E3EF9BDD6F8C78F6D7E006EB938B20B3E3`
- Device: Pixel 10 Pro (`blazer`, serial `56101FDCH006CX`)、Android 17 (`CP2A.260805.005`)。debug APKをinstall後、dark / lightを切り替えて画面をcaptureし、darkへ戻した。画面は `Disabled` 状態で表示し、音声のON/OFFやbackend採用判断は今回の範囲外
- Interaction: `Vintage speaker` をタップして選択状態と説明文が更新されることを確認。プリセット列を横スワイプし、`Weak signal` / `Saturation` / `Fading` が潰れずに表示されることを確認。`android layout` のUI treeにも各ラベルと4つのパネル見出しが存在した
- Stability: UI確認後のfiltered logcatでアプリの `FATAL EXCEPTION` / `ANR in` / RAZIO由来Exceptionはなし。起動時の既存AudioEffect生成ログ（session `0` のEqualizer / DynamicsProcessing）は従来どおり出力された
- Status: **UI初回実装・unit test・build・Pixel表示確認済み。最終デザインの聴感ではなく見た目の受入はユーザー確認待ち**。この記録は初回実装時点のもので、後続のtuning dial / 製品向けsignal meter / icon変更は下記の各記録へ分離した。検証用スペクトラムは別PoCとして追加した

### 2026-08-30 / Mono 感の強化 feasibility check

- Goal: 他アプリのステレオ再生を、現行の session `0` global AudioEffect の範囲で左右混合してモノラル化できるかを確認する
- API確認: 公式 `DynamicsProcessing` は `channelCount` 個の独立した Channel（Input gain → Pre-EQ → MBC → Post-EQ → Limiter）を持つ構成で、`setAllChannelsTo` は各チャンネルへ同じ設定をコピーするAPI。左右のサンプルを加算するmatrix、channel mixdown、出力チャンネル数変換のAPIは公開されていない（[DynamicsProcessing API](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)）
- 現行コード確認: `GlobalAudioEffectController` は端末の実チャンネル数をprobeして同じチャンネル数の `DynamicsProcessing.Config` を生成する。`AmDynamicsConfig` も各チャンネルへ同一のEQ/MBC/Limiter設定を適用するだけで、L/RのPCMを参照する段は存在しない
- 判断: `channelCount=1` を指定しても、global mixのステレオ入力を確実に `(L + R) / 2` へ変換できるとは言えない。effect生成失敗、片チャンネル処理、端末依存のchannel mappingを成功扱いにする危険があるため、Monoトグルや「Mono化できた」という実機未確認の表示は追加しない
- 代替案: 他アプリ音声を真にモノラル化するには `AudioPlaybackCapture` でPCMを取得し、DSPで左右を混合して `AudioTrack` へ再生する必要がある。公式仕様上、MediaProjectionに加え、対象プレイヤーのusage・capture policy・manifest許可に依存する（[AudioPlaybackCaptureConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration.html)）。元音声を抑制できない場合は加工音との二重再生になるため、現行のglobal backendから直接置き換えない
- Verification scope: これは公開APIと現行コードの静的な成立性確認であり、APK変更・実機音質受入は行っていない。Mono は AudioPlaybackCapture/自前再生の別PoCを開始するまでロードマップ上で保留する

### 2026-08-30 / DynamicsProcessing-only production path and deeper high cut

- User decision: 通常の `Equalizer` 経路を廃止し、`DynamicsProcessing` 単体（Pre-EQ flat → MBC → Post-EQ → Limiter）を現行経路に固定する。処理方式のA/B切替UIとEqualizer生成・適用・再試行を削除し、Equalizerの状態は `Not used (backend=dynamics_only)` として観測可能なまま残す
- High-cut adjustment: 全プリセットの `highGainDb` を `-40dB` から `-48dB` へ変更した。10 kHz付近は全プリセットで高域目標へ到達済みなので、`highCutHz`を動かすのではなく終端ゲインを下げる。Post-EQは20 kHz bandまで持つため、Equalizerの端末下限（Pixelで約`-15dB`）に依存しない
- Failure policy: DynamicsProcessingの生成・enableに失敗した場合は `Unsupported` / `Error` を表示し、Equalizerへ暗黙に戻さない。これにより採用経路と端末対応可否を一致させる
- Verification plan: `:app:testDebugUnitTest` / `:app:assembleDebug`、Pixel 10 Pro（Android 17 / Pixel Buds Pro 2 Bluetooth A2DP / Spotify）でsession `0` のeffect数が1、`Equalizer: Not used`、`preEq=flat`、`postEq=curve`、20 kHz band `-48dB` readback、プリセット切替・route change・FGS・crash/ANRを確認する。聴感では10 kHz付近の高域残り、音量、クリック、歪みを比較する
- Unit / build: workflow `03f704c0c89ce0dc3fd3a9daeeae36e9` で `:app:testDebugUnitTest` と `:app:assembleDebug` がPASS。最終APK SHA-256は `116EC8C867447EB6CCE2E2A75AA1431A9229D0307CC875067609B71AF4FAF19D`
- Device structure: Pixel 10 Pro（`blazer`、Android 17 `CP2A.260805.005`、serial `56101FDCH006CX`）、Pixel Buds Pro 2 Bluetooth A2DP、Spotify `PlaybackState=PLAYING` で確認。UIは `Equalizer: Not used (backend=dynamics_only)`、`preEq=flat`、`postEq=curve`。最終APKで Narrow AM / Vintage speaker / Weak signal / Saturation / Fadingを切替え、各Post-EQの9 / 18 / 20 kHz bandが `-48.0dB` になった
- Effect / lifecycle: `dumpsys media.audio_flinger` は session `0` の `DynamicsProcessing` 1 effect（Equalizerなし）。ON/OFFは `Active` ↔ `Disabled`、`dynamics setEnabled actual=true/false` を確認。FGSは `isForeground=true`、Spotify再生は継続した。Bluetoothのdisable / enableでroute change callbackを発生させた後も `DynamicsProcessing` 1 effectとenableを維持し、Pixel Buds Pro 2へ復帰した
- Stability: 検証範囲のfiltered logcatにRAZIO由来のcrash / ANR / `AudioHardening`はなし。`AudioEffect.queryEffects()`の能力一覧にEqualizerBundleが出ても、実際のsession `0` chainへは生成していない
- Post-EQ guard: 生成・再利用時にPost-EQのstage / band数 / 有効状態 / 9・18・20 kHzの高域ゲインをnative readbackし、20 kHz帯が維持できない構成を`Ready`扱いにしない。最終APKでもguard通過後に`Active`を確認した
- Status: **DynamicsProcessing単独化・高域`-48dB`のunit test / build / Pixel構造確認PASS。10 kHz付近の聴感（高域残り・音量・クリック・歪み）はユーザー確認待ち**

### 2026-08-30 / 入出力スペクトラムアナライザー検証PoC

- Goal: プリセットの効果を数値とグラフで比較できるようにし、入力・出力のどちらが取得できているかを隠さず表示する。音声の加工・差し替えは今回のスコープ外
- Implementation: 追加ライブラリは使わず、Android標準の `AudioPlaybackCapture` + `AudioRecord` を入力tap、`Visualizer(0)` を出力mix tapとして利用する。各フレームを1024点のHann窓付きradix-2 FFTへ通し、80 / 160 / 315 / 630 / 1k / 2k / 4k / 6.3k / 10k / 16k Hzの10帯域を`-80..0 dBFS`で描画する。RMSとPeakも同じフレームから計算する。Visualizerは`SCALING_MODE_AS_PLAYED`を優先し、unsigned 8-bit waveformを中心値128からPCMへ戻す
- Input limitation: AudioPlaybackCaptureはAndroid 10以上、`RECORD_AUDIO`、MediaProjection同意、対象アプリのusage / capture policy / profile条件に依存する。取れない場合は `Partial` または `Error` として表示する。入力ラベルは「再生ミックスのコピー」であり、DynamicsProcessing前のraw PCMとは断定しない
- Output limitation: `Visualizer(0)` はglobal output mixの低品質waveform callbackで、厳密なpost-DynamicsProcessing PCM readbackではない。AudioPlaybackCapture側とVisualizer側のどちらがeffect前／後かを、公開APIだけで逆転して断定することもできない。したがってグラフは傾向確認用で、Post-EQの正確な値は従来どおりDynamicsProcessingのnative readbackで判定する
- Double playback: AudioRecordで読んだ入力PCMをAudioTrackへ書き戻していない。解析開始・停止は元音声の再生経路を所有せず、二重再生を作らない
- Permission / FGS flow: UIで録音権限を確認してからMediaProjection同意を起動する。Android 17（API 37）では `MediaProjectionConfig.Builder.setAudioRequested(true)` で音声取得を要求する。同意後に `RazioAudioService` を`mediaProjection`型FGSとして先にforeground化し、準備完了callbackの後で`getMediaProjection`を呼ぶ。解析停止時はProjection型だけを解放し、DynamicsProcessing用`specialUse` FGS所有権は残す
- Device: Pixel 10 Pro（`blazer`、serial `56101FDCH006CX`）、Android 17（`CP2A.260805.005`）、Spotify、Pixel Buds Pro 2 Bluetooth A2DP。`RECORD_AUDIO` / `POST_NOTIFICATIONS`は許可済み
- Reproduction: Spotifyを`PlaybackState=PLAYING`にする → RAZIOの「解析を開始」 → 「RAZIOと画面を共有しますか？」で次へ → アプリ一覧からSpotifyを選択 → RAZIOを前面へ戻す。画面共有中に両グラフが更新され、停止ボタンで`Stopped`へ戻る
- Evidence: 最新debug APK（SHA-256 `B1D5F296860613B2793F7AAFA944BCCEE3FD45012456D0946446DDFFA061A4F5`）でUIが`Active（入力・出力）`、detailが`入力tap=AudioPlaybackCapture / 出力mix tap=Visualizer(session 0) / 前後位置は端末依存 / 元音声は再生しない`となった。最終再確認時の一時値は入力RMS約`-9.9 dB`、Peak約`-0.5 dB`、出力RMS`0.0 dB`、Peak`0.0 dB`（曲・音量・フレームに依存）。`dumpsys media_session`でSpotify `PLAYING`、`dumpsys activity services dev.hondasports.razio`で`isForeground=true`かつProjection型（`0x20`）を確認。停止後`dumpsys media_projection`は`null`、effectのみのspecialUse FGS（`0x40000000`）が残った
- Status: **Pixel 10 Proで入力・出力tap、FFT表示、同意後のFGS順序、停止・Projection解放までPASS**。Visualizer / AudioPlaybackCaptureの仕様上、アプリや出力先によって`Partial`になり得る。これは可視化による検証機能であり、custom DSP / 元音声ミュート / 加工音再生を追加したことを意味しない

### 2026-08-30 / プリセット値の試聴調整UI

- User request: プリセットの各値をスライダーで試せるようにし、周波数6点には明示的な増減操作を追加する
- Design: `AudioPresetTuning` を公開UIモデル、`AudioPresetParameters` をDynamicsProcessing内部モデルとして分離した。周波数（低域カット開始／低域カット中間／中域開始／中域終了／高域カット中間／高域カット開始）、低域・低域中間・中域・高域中間・高域の5ゲイン、MBC ratio／threshold／後段ゲイン、makeup、入力ゲイン、歪み緩和、Fading深度／周期を調整対象とする。低域・高域のロールオフをそれぞれ2段階で追い込める。Compose `Slider` は一定刻みへ丸め、6周波数には`−` / `＋`ボタンを置いた
- Curve preview: 調整パネル内に `PresetFrequencyCurve` を追加した。20 Hz〜20 kHzの対数軸へ細分化グリッドと9個のラベルを配置し、`AudioPresetTuning.gainDbForCenterHz()` の5ゲイン点補間カーブ、低域／高域カット帯と中域帯の網掛け、6つの境界線を描く。カーブは調整値から算出する視覚的な目安であり、native effectの実測値ではない
- Safety: `sanitized()` で周波数順序 `lowCut < lowTransition < midLow < midHigh < highTransition < highCut` と各値の範囲を保証する。`toParameters()` で5ゲイン点とMBC後段ゲイン・makeupを合成し、既存のDynamics-only歪み緩和マッピングを経由してnative effectへ渡す。調整ごとに既存の約80 ms in-place遷移へ合流し、effectをrelease／再生成しない
- Persistence: プリセットごとの調整値はcontroller内のメモリにだけ保持する。DataStoreへ保存せず、再起動で定義済み初期値へ戻る。パネルの`初期値へ戻す`は選択中プリセットだけを初期化する
- Unit / build: workflow `98f47e9a58e21b6d01929165b09c499a` で `:app:testDebugUnitTest` / `:app:assembleDebug` PASS。APK SHA-256 `1ECF131A93B50699FC7BFB12E10009597FA5D4EBE8FCD8E54A0A6F9CA6202249`
- Device: Pixel 10 Pro（`blazer`、Android 17 `CP2A.260805.005`、serial `56101FDCH006CX`）、Pixel Buds Pro 2 Bluetooth A2DP。最終APKで`Narrow AM`を選択し、6周波数スライダーと低域・高域の中間ゲインを含む全ゲイン・MBC・入力・歪み緩和・Fadingスライダー、周波数の`−` / `＋`、リセットを確認した
- Interaction evidence: 周波数カーブ見出し、`+6`〜`-48` dB軸、20 Hz〜20 kHzの細分化対数軸、カット／中域帯の網掛け、6境界線をUI treeとスクリーンショットで確認した。低域カット中間を`420 Hz → 430 Hz → 420 Hz`、高域カット中間を`2600 Hz → 2650 Hz → 2600 Hz`（`＋` / `−`）へ変更すると表示値とカーブ境界が追従した。スライダーでは低域カット開始を`330 Hz`へ移動でき、入力ゲインを`0.0 dB → 3.0 dB`へ移動後、リセットで`0.0 dB`へ復帰した。長めのスライダー操作でもUIは`状態: Active`、Equalizer `Not used (backend=dynamics_only)`、session `0`のDynamicsProcessing effectを維持した
- Stability: `dumpsys activity services dev.hondasports.razio` のeffect用FGSは`isForeground=true`。操作後のfiltered logcatにRAZIO由来のcrash / ANRはなし。各スライダー値がどの音色を最終採用するかは聴感評価で決める

### 2026-08-30 / 同調ダイヤル表示

- Scope: 音声backendを変更せず、プリセット調整パネルへ非インタラクティブな`PresetTuningDial`を追加した。20 Hz〜20 kHzを対数配置したラジオ目盛り上に、低域カット・低域傾斜・中域・高域傾斜・高域カットの色帯と6つの周波数境界を表示する。実際の調整操作は既存の6周波数スライダー／`−` / `＋`ボタンで行う
- Design: ダイヤルは`AudioPresetTuning.sanitized()`後の値から算出する表示専用コンポーネントで、Composeの`modifier`をrootへ受け取る。値やイベントを内部で保持せず、選択中プリセットの状態を親から描画する。周波数カーブと同じ対数軸・境界値を使うため、数値を読まなくても帯域の位置を把握できる
- Unit / build: workflow `35ebdbb46517ab32a3707685900daa2a` で `:app:testDebugUnitTest` / `:app:assembleDebug` PASS。APK SHA-256 `6BB387B70AC9B71245DBAE7AD6F358D3C42DD35E35BE7D31F87A3CCA8C3DBEF5`
- Device: Pixel 10 Pro（`blazer`、Android 17 `CP2A.260805.005`、serial `56101FDCH006CX`）、Pixel Buds Pro 2 Bluetooth A2DPで表示・スクロール・6境界操作を確認した。`Vintage speaker`で低域カット中間を`320 Hz → 330 Hz → 320 Hz`へ操作し、ダイヤル目盛り・周波数カーブ・値表示が追従。`Narrow AM`へ切り替えた後も`300 / 420 / 550 / 2,200 / 2,600 / 3,000 Hz`の6境界を表示した
- Stability: `dumpsys activity services dev.hondasports.razio`でeffect用FGS `isForeground=true`、`dumpsys media.audio_flinger`でsession `0`のDynamicsProcessing 1 effectを確認。操作後のfiltered logcatにRAZIO由来のcrash / ANRはなかった。画面オフ設定は検証後に60秒へ戻した
- Status: **同調ダイヤルのunit test / build / Pixel実機表示・操作確認PASS。ダイヤルは表示専用で、製品向けの操作ダイヤルは未着手**

### 2026-08-30 / 製品向けsignal meter

- Scope: 既存の`Visualizer(session 0)`出力mix tapを、検証用スペクトラムとは別のコンパクトな製品向けレベル表示へまとめた。20段のLED風セグメントをPeak（`-80..0 dB`）から算出し、RMS／Peakの数値と`観測中`／`解析待ち`を表示する
- Limitation: メーターはスペクトラム解析を開始したときだけ更新する。Visualizerは厳密なpost-DynamicsProcessing PCM readbackではなく、エフェクト前後の位置も端末依存のため、音質やカット量の保証値として扱わない。入力PCMを再生し直さないため二重再生は発生しない
- Unit / build: workflow `bb12f7547b375af1b7854c2da7d24ca8` で `:app:testDebugUnitTest` / `:app:assembleDebug` PASS。APK SHA-256 `1F1C0130FBADBA341D6C88D027ECB6113754C61C7F82D4939F3CCD01620D6AFD`
- Device: Pixel 10 Pro（`blazer`、Android 17 `CP2A.260805.005`、serial `56101FDCH006CX`）、Spotify再生、Pixel Buds Pro 2 Bluetooth A2DP。待機時に`解析待ち`とRMS／Peak `−∞ dB`、解析開始後に`Active（入力・出力）`、メーター`観測中`、LED更新、RMS／Peak（例: `-11.4 / -0.7 dB`）を確認した。停止後はProjectionが`null`へ戻った
- Stability: `dumpsys activity services dev.hondasports.razio`でeffect用FGS `isForeground=true`、`dumpsys media.audio_flinger`でsession `0`のDynamicsProcessing 1 effectを確認。操作後のfiltered logcatにRAZIO由来のcrash / ANRはなかった。画面オフ設定は60秒へ戻した
- Status: **製品向けsignal meterのunit test / build / Pixel実機表示・Active更新・停止復帰確認PASS。Visualizer tapの性質上、厳密なpost-DSP測定機能ではない**

### 2026-08-30 / テーマ方針とランチャーブランディング

- Scope: 既存の暖色light / dark schemeをsystem dark modeへ追従させる方針を正本化し、dynamic colorは既定OFFのまま固定した。標準テンプレートの緑色・Androidロボット画像を、ベークライト筐体・紙面パネル・琥珀色の同調部品を描いたRAZIO用adaptive iconへ置き換えた。API 21〜25では同じベクターをlayer-listでフォールバックする
- Unit / build: workflow `cb43afc893e3b21550a161adc848e1bc` で `:app:testDebugUnitTest` / `:app:assembleDebug` PASS。APK SHA-256 `27C0EC772277001895D8DD780F2A1FDB08571A80CA37376B9039BA3049DA25A6`
- Device: Pixel 10 Pro（`blazer`、Android 17 `CP2A.260805.005`、serial `56101FDCH006CX`）。system dark mode（`ui_night_mode=2`）で濃い茶色の背景・パネルと琥珀色の選択状態、light modeで紙面色・錆色の選択状態をcaptureし、検証後にdark modeへ戻した。launcherアプリ一覧の`RAZIO`にレトロラジオ筐体アイコンが表示され、旧テンプレート画像が残っていないことを目視確認した
- Stability: dark / light切替・launcher表示・アプリ再起動後のfiltered logcatに`FATAL EXCEPTION`、`ANR in`、`AudioHardening`、RAZIO由来の未処理Exceptionはなかった。画面オフ設定は60秒へ戻した
- Status: **テーマ方針とランチャーブランディングのunit test / build / Pixel実機表示確認PASS**。この変更は音声backendやeffectの挙動を変更しない

## 判断基準

### Green

- 主要対象アプリで効果が確認できる
- 安定して ON/OFF できる
- 出力先の主要ケースで動く

→ Global AudioEffect 方式を MVP とする。

### Yellow

- 一部アプリ / 出力先だけ動く
- OS バージョン依存が大きい

→ 対応端末を限定するか、複数バックエンド方式を検討する。

### Red

- 対象実機で global AudioEffect が成立しない
- 自アプリ以外の音声に実用上効果がない

→ AudioPlaybackCapture PoC に進む。root 方式へは自動的に移行しない。
