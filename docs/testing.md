# Testing

## 目的

RAZIO では、通常の Android アプリとしての品質に加えて「実際に他アプリ音声へ効果が掛かるか」を検証する必要があります。

テストを以下の4層に分けます。

1. Unit test
2. Android / UI test
3. DSP / parameter test
4. 実機 audio integration test

## 1. Unit test

対象:

- preset の値変換（`AmPresetTest`）
- AudioEffect state transition（`RazioStatusTest`）
- error mapping
- support 判定ロジック
- 設定保存 / 復元

Android framework の AudioEffect を unit test で無理に再現せず、controller の境界を画面状態と preset 計算に分ける。

## 2. Android / UI test

Compose UI test を書く/直す時は `.agents/skills/compose-ui-testing-patterns/SKILL.md`。
テスト基盤そのものを足す時だけ `.agents/skills/testing-setup/SKILL.md` を読む。Hilt / Robolectric / Dropshots を初期セットだからと足さない。詳細は `docs/agent-skills.md`。

最低限確認すること:

- アプリ起動
- ON / OFF
- preset 切り替え
- Unsupported 状態表示
- Error 状態表示
- process recreation 後の設定復元

UI test の成功を AudioEffect の成立証明とは扱いません。

## 3. DSP / parameter test

AudioEffect の band 設定計算など、純粋関数として切り出せる部分は自動テストします。

確認例:

- 低周波 band に負の gain が割り当てられる
- 中域 band が過度に減衰しない
- 高域 band が減衰する
- 端末ごとの band center frequency が違っても正しくマッピングできる
- min / max gain を超えない
- MBC post gain と makeup gain の合計がプリセットごとの設定値になる

将来自前 DSP を導入した場合は WAV / synthetic signal を使った周波数応答テストを追加します。

候補入力:

- 100 Hz sine
- 250 Hz sine
- 1 kHz sine
- 2 kHz sine
- 5 kHz sine
- pink noise

## 4. 実機 audio integration test

RAZIO で最も重要なテストです。

### テストマトリクス

| 項目 | 例 |
| --- | --- |
| Device | Pixel 系など |
| Android | 対象 OS version |
| Target app | YouTube / music / Chrome |
| Output | Speaker / Bluetooth / USB |
| Preset | Narrow AM / Vintage speaker / Weak signal / Saturation / Fading / Shortwave |
| RAZIO | ON / OFF |

### 必須確認

- effect 初期化が成功する
- ON / OFF に明確な差がある
- Narrow AM / Vintage speaker / Weak signal / Saturation / Fading / Shortwave の切替で音の傾向が変わり、選択状態が表示される
- プリセット切替中も session `0` の effect が外れず、音が素通りする区間がない
- 対象アプリ切り替え後も動作する
- 音声 route 変更後の状態
- アプリをバックグラウンドにしても期待通りか
- effect release 後に音が元へ戻る
- 異常終了で effect が残留しない

## 聴感評価

聴感だけに依存しないため、可能な範囲で状態ログを残します。ただし最終的な「AM ラジオらしさ」は人間の聴感評価が必要です。

評価項目:

- 高域が十分に落ちているか
- 低域が十分に落ちているか
- 声が前に出るか
- Narrow AM が300 Hz以下／3.0 kHz以上を抑えた狭いAM放送風、Vintage speaker が450 Hz〜2.6 kHzのかまぼこ型として聞こえるか
- 全プリセットで低域・高域のカットが十分に深く（第2段調整では端部目標をさらに約6dB）、Saturationでも端の音が残りすぎないか
- OFF と比べて音量が過度に小さくならず、かつ不自然に大きくならないか
- 長時間聞いて不快な歪みになっていないか
- Saturation が他プリセットより明確に押し出され、過度なクリップや耳障りな歪みになっていないか
- Fading が音量の急変やクリックを発生させず、数秒周期の受信揺らぎとして聞こえるか

## Regression

AudioEffect 周辺を変更したら、commit 前に最低限 `gradle-run` で `test` と `assembleDebug`。手順は `docs/agent-skills.md`。

実機が利用可能なら:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

まで実施します。

Phase 2 の実機 regression（変更したとき）:

1. RAZIO を ON にして YouTube で効果を確認
2. ON 中に通知が出ること、`dumpsys activity services` で `RazioAudioService` が foreground であること
3. Home へ送って放置し、他アプリ再生で effect が残ること（Pixel 10 Pro で確認済み）
4. 画面 OFF にして約 90 秒放置し、FGS・session 0・聴感が残ること（Pixel 10 Pro で確認済み）
5. `adb shell am force-stop dev.hondasports.razio` のあと起動し、ON が復元されて effect と FGS が付く
6. OFF にして通知が消え、force-stop → 起動し、OFF のまま
7. ON のまま Bluetooth 接続 / 切断し、効果が残るか（Pixel 10 Pro で確認済み。`audio devices removed/added`、`route change wantOn=true`、Dynamics `actual=true`、再接続後 session 0 の1 effect）
8. ON のままプリセットを切り替え、UI の選択状態・session 0 のDynamicsProcessing detail・聴感が切り替わること。切替中に `1 effect for session 0` が維持され、音量差が許容範囲であること
9. Narrow AM → Vintage speaker → Weak signal → Saturation → Fading を短時間に連続選択し、旧プリセットへ瞬間的に戻る音色ジャンプや素通り区間がないこと

2026-08-29 の初回プリセット調整では、Pixel 10 Pro（Android 17）/ SoundCore 2 / Spotify で上記 8・9 を実施し、ユーザー聴感も受入済み。Saturationの入力ゲイン・強い圧縮の聴感もユーザー確認済み。続く全プリセット両端カット再調整（Narrow AM / Vintage speaker / Weak signal / Saturation / Fading）も同じ実機でユーザー受入済み。詳細なPost-EQ値・`dumpsys`・logcat は `docs/audio-research.md` に記録しています。

### Retro radio UI first pass

音声経路を変更しないUI変更でも、Pixelで表示崩れと操作可能性を確認します。

1. `:app:testDebugUnitTest` / `:app:assembleDebug` を `gradle-run` で実行し、生成APKを `adb install -r` する
2. `android screen capture` でsystem dark modeの画面を保存し、暖色背景・パネル枠・選択状態・ステータス表示が読めることを確認する
3. `adb shell cmd uimode night no` でlight modeへ切り替え、同じ操作を確認した後、元のnight modeへ戻す
4. `Vintage speaker` をタップし、選択状態と説明文が更新されることを確認する
5. プリセット列を横スワイプし、`Weak signal` / `Saturation` / `Fading` が1行ラベルで表示されることを確認する
6. `android layout` のUI treeで `RAZIO`、4つのパネル見出し、全プリセット名、Hiss / Crackleが取得できることを確認する
7. filtered `logcat` で `FATAL EXCEPTION`、`ANR in`、アプリ由来の未処理Exceptionがないことを確認する

2026-08-30 の初回実装時点では、Pixel 10 Pro / Android 17でdark / lightの両scheme、`Vintage speaker` 選択、プリセット横スクロール、UI tree、filtered logcatを確認した。tuning dial、製品向けsignal meter、iconは後続変更として別記録へ分離している。検証用スペクトラムは下記の別PoCとして追加した。詳細は `docs/audio-research.md`。

### プリセット値の試聴調整UI

調整値はプリセット単位で DataStore へ保存する。周波数6点は順序を崩さない範囲でスライダーと`−` / `＋`ボタンから変更し、低域・高域の中間ゲインを含むその他のゲイン・MBC・歪み緩和・Fading値もスライダーで変更します。

必須確認:

1. `:app:testDebugUnitTest` / `:app:assembleDebug` を `gradle-run` で通し、debug APKをPixelへinstallする
2. RAZIOをONにしてプリセットを選び、周波数カーブが常時表示されること。`DETAILS / 開く` でパネルが展開され、表示中に画面を縦スクロールして全スライダーへ到達できること
3. カーブのカット帯・中域帯の色分けと6つの縦境界線が周波数境界位置と一致すること。詳細パネルの6本のスライダーは表示中のカーブへ追従すること
4. 低域カット開始／低域カット中間／中域開始／中域終了／高域カット中間／高域カット開始のスライダーを動かし、各`−` / `＋`ボタンで刻み幅どおりに値が増減すること。周波数の順序が逆転せず、同調ダイヤルと周波数カーブの6境界線・網掛け・カーブが変更値へ追従すること
5. ゲイン、MBC、入力ゲイン、歪み緩和、Fading深度・周期を動かし、値表示とエンジンdetailの反映が変わること。調整中もsession `0` のDynamicsProcessingが維持されること
6. 「初期値へ戻す」で選択中プリセットの値へ戻ること。別プリセットへ切り替えて戻ったときは、調整値がプリセット単位で保持されること
7. force-stop後の再起動で、最後に保存した調整値・選択プリセット・Hiss / Crackle が復元されること
8. 操作後のfiltered `logcat` に `FATAL EXCEPTION`、`ANR in`、アプリ由来の未処理Exceptionがないこと

2026-08-30 の実機確認:

- workflow `98f47e9a58e21b6d01929165b09c499a` で `:app:testDebugUnitTest` / `:app:assembleDebug` をPASS。APK SHA-256は `1ECF131A93B50699FC7BFB12E10009597FA5D4EBE8FCD8E54A0A6F9CA6202249`
- Pixel 10 Pro（Android 17 / serial `56101FDCH006CX` / Pixel Buds Pro 2 Bluetooth A2DP）へ最終APKをinstallし、`Narrow AM` の調整パネルを開いた。6周波数スライダーと低域・高域の中間ゲインを含む全ゲイン／MBC／入力／歪み緩和／Fadingの各スライダー、`−` / `＋`ボタン、リセットボタンをUI treeで確認した
- 周波数カーブ見出し、`+6`〜`-48` dBの縦軸、20 Hz〜20 kHzの細分化した対数軸ラベル、低域／高域カット帯・中域帯の網掛け、6つの境界線を表示した。低域カット中間を`420 Hz → 430 Hz → 420 Hz`、高域カット中間を`2600 Hz → 2650 Hz → 2600 Hz`（`＋` / `−`）へ変更すると表示値とカーブ境界が追従した
- 既存確認として、スライダーでは低域カットを`330 Hz`へ変更でき、入力ゲインは`0.0 dB → 3.0 dB`へ移動後、リセットで`0.0 dB`へ復帰した。長めのスライダー操作でもUIは`状態: Active`、Equalizerは`Not used (backend=dynamics_only)`を維持し、session `0` のDynamicsProcessing effectが外れなかった
- `dumpsys activity services dev.hondasports.razio` でeffect用FGS `isForeground=true`を確認し、操作後のfiltered logcatにRAZIO由来のcrash / ANRはなかった。これは操作・構造確認であり、各値の最終的な聴感プリセット決定はユーザー確認待ち

### 製品UI（現行）

第一面はラジオ製品面にする。FilterChip、`Active` / `session 0` / `DynamicsProcessing` の状態チップ、6本スライダーのスタックは主画面に出さない。`RazioHomeScreen` は大きな `RAZIO`、円形電源、`LIVE` / `STANDBY`、プリセットの `‹ 名前 ›` と点インジケータ、塗りカーブ、細い出力メーター、Hiss / Crackle のスイッチと `0〜400%` スライダー、`詳細設定を開く` で構成する。プリセットは `‹ ›`、点、名前／カーブ上の横スワイプで切り替える。blurb直下の `（プリセット名）を初期値に戻す` は常時表示し、定義値のときは非活性、変更後はアンバーの活性表示になる。ヘッダーの電源は既存の `onPowerChange` へ接続する。`詳細設定を開く` の折りたたみは `rememberSaveable(state.preset.id)` で保持し、開くと周波数スライダー、同調ダイヤル、ゲイン / Dynamics / Character、Noise状態 / Spectrum / Engine 検証パネルを展開する。`session 0` の観測は Engine パネルへ残す。

必須確認:

1. debug APKをPixelへinstallし、第一面に `RAZIO`、円形 ON/OFF、`LIVE` または `STANDBY`、プリセット名、塗りカーブ、細いメーターが出ること。`session 0` / `DynamicsProcessing` チップ、6本スライダー、緑黒CRTが出ないこと
2. 名前またはカーブを左／右へスワイプして隣接プリセットへ変わり、点インジケータとカーブ形状が追従すること。`‹ ›` と点タップでも同じ切替になること。6つ目の `Shortwave` が点とスワイプに含まれること
3. `詳細設定を開く` を押して `詳細設定を閉じる` へ変わり、同調ダイヤルと6つの周波数ラベル（低域カット開始／低域カット中間／中域開始／中域終了／高域カット中間／高域カット開始）がUI treeに現れること
4. 6本のうち1本を `＋` またはスライダーで変更し、値表示と第一面カーブが更新されること。blurb直下の `（プリセット名）を初期値に戻す` が非活性から活性へ変わり、タップで定義値へ戻って再び非活性になること
5. 詳細の開閉・リセット後も `Equalizer: Not used`、session `0` のDynamicsProcessing、第一面の Hiss / Crackle、スペクトラム／出力メーターの既存操作が崩れないこと
6. 操作後のfiltered `logcat` に `FATAL EXCEPTION`、`ANR in`、アプリ由来の未処理Exceptionがないこと

2026-08-30 の Ghost Terminal UI 確認と、設定画面風チップ＋6スライダーの第一面は履歴として残し、現行の見た目判定には使わない。

### 同調ダイヤル表示

- workflow `35ebdbb46517ab32a3707685900daa2a` で `:app:testDebugUnitTest` / `:app:assembleDebug` をPASS。APK SHA-256は `6BB387B70AC9B71245DBAE7AD6F358D3C42DD35E35BE7D31F87A3CCA8C3DBEF5`
- Pixel 10 Pro（Android 17 / serial `56101FDCH006CX` / Pixel Buds Pro 2 Bluetooth A2DP）へAPKをinstallし、調整パネルの同調ダイヤルを表示した。`Vintage speaker`で低域カット中間を`320 Hz → 330 Hz → 320 Hz`へ変更すると、ダイヤルの境界目盛り、周波数カーブ、値表示が追従した。`Narrow AM`へ切り替えた後もダイヤルの6境界が表示された
- filtered `logcat`に`FATAL EXCEPTION`、`ANR in`、RAZIO由来の未処理Exceptionはなく、`dumpsys activity services`でeffect用FGS `isForeground=true`、`dumpsys media.audio_flinger`でsession `0`のDynamicsProcessing 1 effectを確認した。検証後の画面オフ設定は60秒へ戻した

### 製品向けsignal meter

- workflow `bb12f7547b375af1b7854c2da7d24ca8` で `:app:testDebugUnitTest` / `:app:assembleDebug` をPASS。APK SHA-256は `1F1C0130FBADBA341D6C88D027ECB6113754C61C7F82D4939F3CCD01620D6AFD`
- Pixel 10 Pro（Android 17 / serial `56101FDCH006CX` / Pixel Buds Pro 2 Bluetooth A2DP）へAPKをinstallし、出力レベルメーターの待機状態（`解析待ち`、RMS／Peak `−∞ dB`）を確認した
- Spotify再生中に既存のスペクトラム解析を開始し、UIが`Active（入力・出力）`となることを確認。出力mix tapのメーターが`観測中`になり、LEDセグメント、RMS、Peak（例: `RMS -11.4 dB` / `Peak -0.7 dB`）が更新された。停止後はProjectionが解放され、メーターは待機表示へ戻った
- filtered `logcat`にRAZIO由来のcrash / ANRはなく、effect用specialUse FGSとsession `0`のDynamicsProcessing 1 effectを維持した。メーターは出力tapの傾向表示であり、native effectの厳密なpost-DSP測定値ではない。検証後の画面オフ設定は60秒へ戻した

### テーマ方針とランチャーブランディング

テーマはsystem dark modeに追従する暖色light / dark schemeを採用し、端末壁紙へ色が引っ張られるdynamic colorは既定で無効にします。ランチャーはベークライト筐体・紙面パネル・琥珀色の同調部品を描いたRAZIO用adaptive iconを使い、API 21〜25では同じベクターをlayer-listで表示します。

必須確認:

1. `:app:testDebugUnitTest` / `:app:assembleDebug` を `gradle-run` で通し、生成APKをPixelへinstallする
2. `adb shell cmd uimode night yes` でdark mode、`adb shell cmd uimode night no` でlight modeへ切り替え、背景・パネル・文字・選択色が読めることを確認する
3. launcherのアプリ一覧を開き、緑色の標準テンプレートではなくRAZIOのラジオ筐体アイコンが表示されることを確認する
4. icon確認後にsystem dark modeと画面オフ設定を検証前の値へ戻す
5. filtered `logcat`で `FATAL EXCEPTION`、`ANR in`、RAZIO由来の未処理Exceptionがないことを確認する

2026-08-30 の実機結果:

- workflow `cb43afc893e3b21550a161adc848e1bc` で `:app:testDebugUnitTest` / `:app:assembleDebug` をPASS。APK SHA-256は `27C0EC772277001895D8DD780F2A1FDB08571A80CA37376B9039BA3049DA25A6`
- Pixel 10 Pro（Android 17 / serial `56101FDCH006CX`）へinstallし、dark mode（`ui_night_mode=2`）で濃い茶色の背景・パネル、light modeで紙面色・錆色の選択状態をcaptureした。launcherアプリ一覧の`RAZIO`にレトロラジオ筐体アイコンが表示され、標準テンプレート画像が残っていないことを確認した
- filtered `logcat`は`FATAL EXCEPTION` / `ANR in` / `AudioHardening` / RAZIO由来の未処理Exceptionなし。検証後のsystem dark modeと画面オフ設定は元の値（dark / 60秒）へ戻した

### 入出力スペクトラムアナライザー検証PoC

この機能は、音声を再生し直さずに入力・出力を比較するための観測tapです。入力は `AudioPlaybackCapture` → `AudioRecord` をエフェクト前の基準として使い、出力は同じ入力フレームへ現在の `DynamicsProcessing` を反映したエフェクト後の推定値です。どちらも同じ1024点FFTで10帯域へ変換し、`Active` / `Partial` / `Error` として取得可否を表示します。native post-DSP PCMは公開APIで保証できないため、入力キャプチャが使えない場合だけ `Visualizer(session 0)` をfallbackにし、UIでも推定値と区別します。

必須確認:

1. `:app:testDebugUnitTest` と `:app:assembleDebug` を `gradle-run` で通し、debug APKをPixelへinstallする
2. Spotifyなど対象アプリを再生し、RAZIOの「解析を開始」を押す。`RECORD_AUDIO`許可後、MediaProjectionの画面共有同意を通す（Android 17では音声取得を要求するUIになる）
3. UIが `Active（入力・出力）` になり、入力／出力の棒グラフ、RMS、Peakが更新されることを確認する。入力側は `入力（エフェクト前） / AudioPlaybackCapture`、出力側は `出力（エフェクト後・推定） / DynamicsProcessing` と表示され、detailが `入力tap=AudioPlaybackCapture（エフェクト前の解析用コピー）` / `出力=同一フレームへDynamicsProcessingを反映したpost-effect推定` になることを記録する。入力キャプチャ失敗時は `Visualizer(session 0; post-DSP非保証)` のfallback表示になることも確認する
4. `dumpsys media_session`で対象アプリが`PLAYING`、`dumpsys activity services dev.hondasports.razio`でProjection型FGSがforegroundであることを確認する
5. 入力PCMをAudioTrackへ再生していないため、解析開始前後で二重再生・意図しない音量二重化がないことを聴感確認する
6. 「解析を停止」を押し、UIが`Stopped`、`dumpsys media_projection`が`null`になることを確認する。RAZIOの電源がONならeffect用specialUse FGSだけが残ることを確認する
7. 対象アプリのcapture policyやProjection拒否を再現できる場合、`Partial` / `Error`と理由が表示され、元音声を抑制しないことを確認する

2026-08-30 の実機結果（旧Visualizer出力方式の履歴）:

- Pixel 10 Pro（Android 17 / Pixel Buds Pro 2 Bluetooth A2DP）でSpotify `PlaybackState=PLAYING`を再生し、画面共有のアプリ選択でSpotifyを指定した。UIが`Active（入力・出力）`となり、両グラフのフレームが更新された（最終再確認時の一時値: 入力RMS約`-9.9 dB`、出力RMS`0.0 dB`。曲・音量・フレームに依存）
- `dumpsys activity services`でProjection型FGS `isForeground=true`、停止後に`dumpsys media_projection`が`null`、Spotify再生継続を確認した。アプリのcrash / ANRはなし
- `:app:testDebugUnitTest` / `:app:assembleDebug` はworkflow `5a5253f24ae29370dc7f3a53472a2221`でPASS。入力の音声を再生し直さないため、二重再生を作らない構造をコードとUI detailで確認した

2026-08-31 のpre/post表示変更結果:

- `SpectrumEffectEstimatorTest`を含む `:app:testDebugUnitTest` はworkflow `00db3a3dff5a7dc2aa4eae2d5768cfd5` でPASS。入力フレームを変更しない無効時、Narrow AMの高域カット・リミッター上限を確認した。`:app:assembleDebug` と `:app:lint` も同workflowでPASSし、debug APK SHA-256は `80BE61BF8082FD7473AE38908E09C50D7EFDA79FDFAAB16111D478A16D092CCD`
- Pixel 10 Pro（`blazer`、Android 17、serial `56101FDCH006CX`）へAPKを再installし、Spotify `PlaybackState=PLAYING`・Pixel Buds Pro 2 Bluetooth A2DP出力中にMediaProjection同意を完了した。UIが `Active（入力・出力）` となり、入力ラベル `入力（エフェクト前） / AudioPlaybackCapture` と出力ラベル `出力（エフェクト後・推定） / DynamicsProcessing`、detailの `post-effect推定` を確認した。画面上の一例は入力 `RMS -12.9 dB / Peak -3.7 dB`、出力 `RMS -14.8 dB / Peak -1.2 dB` で、高域カットを反映した出力バーになった
- `dumpsys activity services dev.hondasports.razio` はeffect／ProjectionのFGSを `isForeground=true`・`types=0x40000020` と表示し、`dumpsys media_projection` はpackage `dev.hondasports.razio` のstarted projectionを表示した。`dumpsys media.audio_flinger` はsession `0`のDynamicsProcessing／VisualizerとREMOTE_SUBMIXのactive trackを確認した。入力PCMは再生しないため二重再生は発生しない
- native post-DSP PCMは公開APIで保証できないため、出力は推定値として扱い、入力キャプチャ不能時のsession `0` Visualizerは `post-DSP非保証` のfallbackに限定した。filtered `logcat` には今回の操作に伴うRAZIO由来の `FATAL EXCEPTION`、`ANR in`、`AudioHardening` はなかった
- 解析停止後はUIが `Stopped` に戻り、`dumpsys media_projection` の `Media Projection` が空、`dumpsys media.audio_flinger` のREMOTE_SUBMIX patchがrelease済み（`No active record clients`）になった。Spotifyの再生は継続した
- 追試（2026-08-31）: HAL readback失敗後にUIレポートだけが `Unsupported` となり、推定出力が入力へ戻る条件を修正。修正版APK（SHA-256 `57BEBC8E9804B9869103C62903EF4FCA3B51D0359B95BBC2E48D43C50CF2B096`）をPixel 10 Proへ再installし、Spotify再生・Pixel Buds Pro 2 A2DP・MediaProjection同意後に `Active（入力・出力）` を確認した。入力は `RMS -9.7 dB / Peak -0.8 dB`、出力推定は `RMS -35.0 dB / Peak -11.7 dB` で、Weak signalの高域カットが出力バーへ反映された。出力のdetailは引き続き `post-effect推定` とし、native post-DSP PCMの実測とは扱わない

2026-08-31 のcapture不可アプリ確認:

- radiko（`jp.radiko.Player`）を `PlaybackState=PLAYING` にし、Pixel 10 Pro / Android 17 / Pixel Buds Pro 2 A2DPでMediaProjectionの共有対象へ指定した。`AudioRecord` remote-submix trackはactiveだったが、有効信号は得られず、修正前は `Active（入力・出力）` のままRMS／Peak `−∞ dB` になった
- 入力開始後2秒間、表示床（約`-80 dBFS`）を超えるpeakが無い場合に入力をunavailableへ戻すヘルス判定を追加。最新APK（SHA-256 `7584A65FC15111F3A8E3EB5B32661270B78E526DBA384F28A591FB6B873E7F43`）ではUIが `Partial（片側のみ）`、detailが `入力信号なし（無音または対象アプリのcapture policy制限の可能性）` となり、入力側に `信号待ち` を表示した（画面: `C:\Users\tatsuya\AppData\Local\Temp\razio-spectrum-evidence\radiko-partial-detail.png`）
- 同じ解析セッションでradikoを停止してSpotifyを再生すると、UIが `Active（入力・出力）` へ復帰し、入力 `RMS -17.2 dB / Peak -6.3 dB`、出力推定 `RMS -23.1 dB / Peak -3.7 dB` を確認した（画面: `C:\Users\tatsuya\AppData\Local\Temp\razio-spectrum-evidence\spotify-active-postfix.png`）。入力PCMを再生し直さないため、解析開始による二重再生は発生しない
- 継続監視の追試では、最新APK（SHA-256 `81F21C86E98672868E2EC5E7A173BBDE202D4206DCBAE1DFF388D2937473DC20`）でSpotify解析中にメディアを一時停止し、4秒後に `Partial（片側のみ）` と入力信号なし理由へ遷移した。再生を再開すると約3秒で `Active（入力・出力）` へ戻り、入力 `RMS -6.7 dB / Peak -0.1 dB` を確認した（画面: `C:\Users\tatsuya\AppData\Local\Temp\razio-spectrum-evidence\continuous-health-partial.png` / `continuous-health-active.png`）
- 停止後はUI `Stopped`、`dumpsys media_projection` 空、`No active record clients`。capture policyの具体値は公開APIで取得できないため、原因はpolicy制限または無音の可能性として表示し、成功・失敗を断定せずにPartialへ落とす

### Mono passthrough mixdown PoC（削除済み・履歴）

これは2026-08-31に実施したが、現在の製品APKからは削除済みの履歴です。`MEDIA` / `GAME` / `UNKNOWN` usageに一致する再生ミックス全体（アプリUID指定なし）を、元アプリの再生を止めずに `AudioPlaybackCapture` で取得し、PCMを `(L + R) / 2` へ混合してstereo `AudioTrack`へ返していました。元音声のミュートができず、ユーザー聴感で二重化が明確だったため、Mono差し替えbackendは不採用とし、以下の手順は再実行しません。

削除前の確認手順（参照用・現行APKでは実行不可）:

1. `:app:testDebugUnitTest` / `lint` / `:app:assembleDebug` を `gradle-run` で通し、debug APKをPixelへinstallする
2. RAZIOをONにして対象アプリ（Spotify / radiko等）を再生し、詳細パネルの `Mono PoCを開始` を押す。`RECORD_AUDIO`許可後、Android 17では音声取得を要求したMediaProjection同意を通す
3. UIが `Active（stereoをmixdown）` になり、`capture=2ch / output 2ch / 48000Hz` と概算遅延、capture/output frame counterが表示されることを確認する。stereo初期化に失敗した場合は `Partial（mono capture）` であり、L/R混合成功とは判定しない。2ch形式は確認できても、左右に独立した元信号が入っていた証明にはならない
4. `dumpsys media_session`で対象アプリが`PLAYING`、`dumpsys audio` / `dumpsys media.audio_flinger`で対象アプリとRAZIOのAudioTrackが同時に`started` / `active`、`dumpsys activity services dev.hondasports.razio`で`types=0x40000020`を確認する。RAZIOがAudioFocusを奪っていないことも確認する
5. 元音声の抑制は未実装なので、元音声が継続して聞こえることを確認し、二重再生の音量差・エコー・遅延をメモする。元音声が消えた場合も成功扱いにせず、capture policy / focusの一次ログを残す
6. `Mono PoCを停止`を押す。UIが`Stopped`、`dumpsys media_projection`が`null`、RAZIOのPoC AudioTrackとmediaProjection型FGSが消え、対象アプリだけが継続することを確認する
7. PoC実行中にRAZIO電源をOFFにし、同じ解放が行われることを確認する。route change・画面OFF・Projection status bar chip停止は別試行として記録する
8. Activity taskをスワイプ終了し、Mono/Spectrumのcapture track・projection・FGSが解放されることを確認する（global effect ownerは別途継続し得る）
9. filtered `logcat`で`mono playback PoC`、`FATAL EXCEPTION`、`ANR in`、`AudioHardening`の新規出力を保存する。失敗時はUIの`Error` / `Partial`理由を優先し、無言で通常backendへfallbackしたと扱わない

2026-08-31 の実機結果:

- Pixel 10 Pro（`blazer`、Android 17、serial `56101FDCH006CX`）、radiko（`jp.radiko.Player`）、Pixel Buds Pro 2 Bluetooth A2DPでMediaProjection同意後に`Active（stereoをmixdown）`を確認。最新版APKの表示は`capture=2ch / output 2ch / 48000Hz / 概算遅延 506ms`（過去試行は約170 msで、buffer依存）。frame counterは同じ処理ループの進捗値としてcapture/outputとも増加したが、独立したread/write完全性や左右独立信号の証明ではない
- `dumpsys audio`でradikoとRAZIOのstereo AudioTrackが同時に`state=started`、`dumpsys media.audio_flinger`でも両trackが`active`。RAZIO側は`FLAG_NO_SYSTEM_CAPTURE`（`ALLOW_CAPTURE_BY_NONE`）で、radikoの再生状態は維持された。実行中のFGSは`isForeground=true` / `types=0x40000020`
- 停止ボタンでUIが`Stopped`、`dumpsys media_projection`が`null`、RAZIO AudioTrackが消え、specialUse FGSだけが残った。電源OFFでもMono trackとFGSが消え、radikoだけが継続した。ONへ戻すとDynamicsProcessing用specialUse FGSを再確認した
- MediaProjection同意ダイアログ表示中に開始ボタンを再タップしても、Projection permission activityが1つのまま増殖せず、背面UIには同意待ちメッセージを表示した
- clean logcat（開始前に`adb logcat -c`）ではRAZIO由来の`FATAL EXCEPTION` / `ANR in` / `AudioHardening`新規出力なし。ユーザー聴感で元音声と加工音の二重化が明確に確認され、Monoは不可と判定した。これはdownmix処理の失敗ではなく、通常アプリから元音声をミュートできない経路上の制約である。したがって2ch capture/downmix再生ループは技術PoCとして成立したものの、Mono差し替えbackendの製品採用はNo-go（不採用）とし、PoCのcontroller・mixer・UI・service連携・unit testも削除した。RecentsからActivity taskをスワイプ終了した試行では削除前のMono停止ログ、projection消失、Mono AudioTrack消失、global effect ownerのspecialUse FGS継続を確認した

### Hiss / Crackle AudioTrack overlay PoC

元音声を `AudioPlaybackCapture` でコピーして再生するのではなく、RAZIO生成ノイズだけを `AudioTrack` で同時再生する検証。PoCの実装・測定条件は `docs/audio-research.md` の計画に合わせる。

必須確認:

1. 画面表示中にPoCを開始し、既存のRazioAudioServiceをforegroundにしたままSpotify / YouTube / radikoを再生
2. 本体スピーカーとSoundCore 2 Bluetoothで、対象アプリがpause・意図しないduckを起こさずノイズが聞こえること
3. RAZIO OFF、route切替、Home、画面OFF、対象アプリpauseでノイズが止まり、残留Runnable / AudioTrackがないこと
4. `dumpsys media_session` / `dumpsys audio` / `dumpsys activity services dev.hondasports.razio` / `dumpsys media.audio_flinger` と `AudioHardening`・crash・ANRのfiltered logcatを保存
5. 元音声を捕捉して再再生しないため、sourceの二重再生や捕捉許可によるアプリ差をPoCの合否から分離して記録

2026-08-30 の実装・構造確認結果:

- `NoiseOverlayController` が決定論的なPCMを生成し、`USAGE_MEDIA` / `CONTENT_TYPE_UNKNOWN`・AudioFocusなしの独立 `AudioTrack` で再生する。AudioPlaybackCaptureは使わず、元音声のミュート／差し替えもしない。RAZIO OFFではAudioTrackとFGSを停止するがスイッチとレベルは保持し、再ONとforce-stop後はDataStoreから復元する。route changeでは停止・再生成する
- `:app:testDebugUnitTest` と `:app:assembleDebug` は workflow `2874600e19b809682ef98d192f8cbe31` でPASS。最新版検証APKのSHA-256は `678780EA7C35DF1ED3F99ED3A4A30B56B7696669B340FD54BAAE95C10DCDC9AC`
- Pixel 10 Pro（`blazer`、Android 17 `CP2A.260805.005`、serial `56101FDCH006CX`）でSpotify再生中にHiss / Crackleを有効化し、`dumpsys audio`でSpotifyとRAZIOの独立AudioTrackが同時に `started` になることを確認した。RAZIOはmono / 48 kHz、detailは `usage=media content=unknown focus=none`
- Home・画面OFF（`Dozing`）でも既存のspecialUse FGSとRAZIO AudioTrackが維持された。RAZIO OFFではUIが `Disabled` / `Idle`、FGSとRAZIO AudioTrackが消えることを確認した
- Bluetooth切断で出力先がspeaker（device id `3`）へ移り、再接続でPixel Buds Pro 2（device id `7241`）へ戻った。route logには `noise overlay stopped reason=route_change` と再開を確認した。再接続時に既存DynamicsProcessingのroute readbackが `UnsupportedOperationException: AudioEffect: invalid parameter` になる警告が1件出たが、既存fallbackでeffectを再初期化し、ノイズAudioTrackは維持された
- Home・画面OFF・route切替のlogcatをクリアして確認した範囲では、新規の `AudioHardening`、アプリcrash、ANRはなかった。ただし過去のプロセス更新時にAndroid 17の `AudioHardening` ログが出た履歴はあり、長時間のバックグラウンド聴感は別途確認する
- **聴感受入済み（ユーザー「OK」）**。`/sdcard/Download/razio-silence-10s.wav`（48 kHz / mono）をVLCで再生し、ループ設定なしの短時間試行でHiss / Crackleが無音ベースへ重なって聞こえることを確認した。VLC側のループ再生と長時間バックグラウンド聴感は未検証だが、独立ノイズAudioTrackの出力確認というPoC目的は達成したため、製品採用・commitへ進む

レベル調整の追加確認:

1. 第一面で `Hiss` / `Crackle` スイッチと `Hiss 強さ` / `Crackle 強さ` が表示され、`0〜400%`を5%刻みで変更できること
2. Hissだけ、Crackleだけをそれぞれ0%→100%→400%へ動かし、他方のスイッチ状態とAudioTrackを維持したまま聴感の強さが変わること
3. 変更後にRAZIOをOFFへ戻すとノイズAudioTrackが停止し、再ON時は最後のスイッチとレベルが復元されること。force-stop後の再起動でもDataStoreから復元されること

2026-08-31 のPixel確認結果:

- Pixel 10 Pro（`blazer`、Android 17、serial `56101FDCH006CX`）へdebug APK（SHA-256 `DCF8C255B3F205EC03CBEB7D454AE0F22BDBFF73C7B2F58CDAC20E9D6C76782D`）をinstallし、詳細パネルの `Hiss 強さ` / `Crackle 強さ` と0〜200%の5%刻みスライダーをUI treeで確認した
- Hissを `100% → 0% → 200%`、Crackleを `100% → 0% → 200%` の順で個別操作した。UI表示は各値へ追従し、両スイッチON時の `ノイズ: Active` と `sampleRate=48000Hz buffer=40404B` を維持した。スライダー操作前後でsession `21969` が変わらず、AudioTrackを再生成せず次のPCMバッファへ反映することを構造確認した
- RAZIO電源OFFで `session 0 :: Disabled` / `ノイズ: Idle`、`dumpsys media.audio_flinger` の `AT::remove` とtrack idleを確認。再ON後にNoiseスイッチをONへ戻すと `ノイズ: Active` と各 `200%` 表示が復元された（再ON時の新sessionは既存の再生成lifecycleによる）
- 本試行のfiltered `logcat`にRAZIO由来の `FATAL EXCEPTION` / `ANR in` / `AudioHardening` はなかった。UI証跡は [noise-level-200.png](C:/Users/tatsuya/AppData/Local/Temp/razio-spectrum-evidence/noise-level-200.png)

2026-09-03 の製品面・永続化確認:

- Pixel 10 Pro（Android 17、serial `56101FDCH006CX`）で第一面の `ノイズ` / Hiss / Crackle と `0〜400%` スライダー、6つ目の `Shortwave` を確認した
- Shortwave の低域カット開始 `510 Hz` と Hiss ON / `110%` が force-stop 後も復元された。詳細は `docs/audio-research.md`

### DynamicsProcessing単独経路

現行経路は `DynamicsProcessing` 1つだけ（Pre-EQ flat → MBC → Post-EQ → Limiter）とする。通常の `Equalizer` は生成せず、UIのEqualizer欄は `Not used (backend=dynamics_only)` になる。Post-EQの最終bandは20 kHzまで持ち、全プリセットの高域目標は `-48 dB` とする。

1. Spotifyを再生し、本体スピーカー / Pixel Buds Pro 2 Bluetooth A2DPでRAZIOをONにする
2. UI detailで `Equalizer: Not used`、`preEq=flat`、`postEq=curve`、`postEqBands` の20 kHz bandが確認できることを記録する
3. UI detailのPost-EQ 9 / 18 / 20 kHzが`-48dB`、stageが`inUse` / `enabled`、9 bandが全て有効であることをreadbackする。満たさない端末は`Ready` / `Active`合格にしない
4. Narrow AM / Vintage speaker / Weak signal / Saturation / Fading / Shortwaveを順に選択し、10 kHz付近の高域残り、低域・声域のバランス、音量低下、過度な歪みを聴感メモへ残す
5. ON / OFF、プリセット切替、route change、Home、画面OFF、force-stop後の復元を確認する。プリセット切替は約80 msで途切れず、effectを二重生成しないことを確認する
6. `dumpsys media.audio_flinger`、`dumpsys activity services dev.hondasports.razio`、`dumpsys audio`、filtered `logcat`でeffect数（1）、FGS、route、crash/ANRを保存する

合否は、Pixelの両出力先・SpotifyでDynamicsProcessing 1 effectが安定し、`-48 dB`の高域readback、音量低下・クリック・過度な歪みがないこと。DynamicsProcessingが利用できない端末は `Unsupported` / `Error` として記録し、Equalizerへ戻ったことを合格扱いにしない。

2026-08-29 のA/B構造確認（旧PoC）では、Pixel 10 Pro / Android 17 / SoundCore 2 / SpotifyでAはsession `0` の2 effects、BはDynamicsProcessing 1 effectとなり、UI detail・FGS・切替時のrelease / recreate / enableを確認した。旧BのPre-EQは4.5kHzより上を処理せず、MBC後段makeup gainが低域カットを戻し得たため、BをPost-EQ（20kHzまで）へ修正した。その後のユーザー判断でBを採用し、AのSplit切替は削除した。過去のA/B readbackは履歴として保持し、現行の合否は下記の単独経路確認で判定する。
続く過去の調整では、全プリセットの低域・高域目標、MBC、Post-EQ上限を段階的に再調整した。詳細なworkflow・APK hash・旧Splitのreadbackは履歴として残すが、現行コードが参照するのはDynamicsProcessing単独のPost-EQカーブだけである。

2026-08-30 の現行経路確認では、workflow `03f704c0c89ce0dc3fd3a9daeeae36e9` のunit test / debug APK buildをPASS（最終APK SHA-256 `116EC8C867447EB6CCE2E2A75AA1431A9229D0307CC875067609B71AF4FAF19D`）。Pixel 10 Pro（Android 17 / Pixel Buds Pro 2 / Spotify）で5プリセットを切替え、UIの `Equalizer: Not used`、`preEq=flat`、`postEq=curve`、各Post-EQの9 / 18 / 20 kHz `-48dB`をreadbackした。生成・再利用時のPost-EQ guard（stage有効、9 band、全band有効、高域ゲイン、20 kHz終端）も通過した。`dumpsys media.audio_flinger` はsession `0` のDynamicsProcessing 1 effectのみ、ON/OFFは `Active` / `Disabled`、FGSは `isForeground=true`、Spotifyは `PLAYING` を維持した。Bluetooth disable / enableによるroute change後も同じ1 effectとenable状態を確認し、RAZIO由来のcrash / ANR / `AudioHardening`はなかった。10 kHz付近の聴感受入は2026-08-31にユーザー「OK」で完了した。

## テスト不能時

実機テストが必要なのに端末が接続されていない場合、テスト済みとは扱いません。commit して完了にもしません。

残すこと:

- 自動テスト: 実施済み / 未実施
- build: 成功 / 失敗
- 実機テスト: 実施済み / 未実施
- 未実施理由
- 次に必要な具体的手順

「実行できなかったので問題なしとして進める」という判断は禁止します。
