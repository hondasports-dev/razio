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
