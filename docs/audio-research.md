# Audio Research

## 目的

RAZIO の成立性を、Android の仕様と実機挙動の両面から記録します。

## 重要な前提

### Global AudioEffect

Android では audio session `0` を global output mix として扱う AudioEffect の利用方法が歴史的に存在します。

ただし insert effect（Equalizer など）を session `0` に付けることは **deprecated**。端末ごとの Audio HAL / effect implementation に依存し、生成できたことと他アプリへ効くことは別問題です。

PoC 実装:

- `Equalizer(priority, 0)` と `DynamicsProcessing(priority, 0, config)` を独立に試す
- `MODIFY_AUDIO_SETTINGS` を manifest に入れる
- 生成・enable・release の成否を `RAZIO/AudioEffect` タグで出す
- 効果オブジェクトは `Application` 生存期間。ON 中は `RazioAudioService`（specialUse FGS）でプロセスを維持する

そのため、RAZIO では「API が存在する = 必ず全アプリに効く」と判断しません。

## PoC で確認すること

### 1. Effect の生成

- `Equalizer(priority, 0)` が生成できるか
- `DynamicsProcessing(0, ...)` が生成できるか
- `enabled = true` が成功するか
- 例外 / error code

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
- Status: **PoC実装・実機測定待ち**。global AudioEffect のHiss/Crackle非対応という判断は維持し、PoCが通った場合だけ「独立ノイズオーバーレイ」として別backend候補に昇格する

### 2026-08-29 / Fading first pass

- Scope: Hiss / Crackle のような独立ノイズを追加せず、global AudioEffect の DynamicsProcessing input gain だけを周期変動させる
- Fading: Narrow AM と同じ EQ / MBC（input gain 0 dB）を維持し、input gain を ±3 dB、3.2 秒周期、100 ms tick で更新する
- Lifecycle: プリセット遷移完了後に変調を開始し、別プリセットへの切替・OFF・route change・release で Runnable をキャンセルする。更新失敗時も変調を停止し、既存の effect failure path に任せる
- Unit: `AudioPreset.FADING` の帯域、変調深度、周期を確認済み
- 実機: **ビルド・インストール後の Pixel 10 Pro / SoundCore 2 / Spotify 聴感確認待ち**

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
