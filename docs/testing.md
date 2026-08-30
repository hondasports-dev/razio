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
| Preset | Narrow AM / Vintage speaker / Weak signal / Saturation / Fading |
| RAZIO | ON / OFF |

### 必須確認

- effect 初期化が成功する
- ON / OFF に明確な差がある
- Narrow AM / Vintage speaker / Weak signal / Saturation / Fading の切替で音の傾向が変わり、選択状態が表示される
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

2026-08-30 の初回実装では、Pixel 10 Pro / Android 17でdark / lightの両scheme、`Vintage speaker` 選択、プリセット横スクロール、UI tree、filtered logcatを確認した。tuning dial、製品向けsignal meter、iconはまだ追加していない。検証用スペクトラムは下記の別PoCとして追加した。詳細は `docs/audio-research.md`。

### プリセット値の試聴調整UI

調整値はプロセス内だけの試聴用で、DataStoreへ保存しません。周波数は順序を崩さない範囲でスライダーと`−` / `＋`ボタンから変更し、その他のゲイン・MBC・歪み緩和・Fading値もスライダーで変更します。

必須確認:

1. `:app:testDebugUnitTest` / `:app:assembleDebug` を `gradle-run` で通し、debug APKをPixelへinstallする
2. RAZIOをONにしてプリセットを選び、「調整を開く」でパネルが表示されること。表示中に画面を縦スクロールして全スライダーへ到達できること
3. 低域カット／中域開始／中域終了／高域カット開始のスライダーを動かし、各`−` / `＋`ボタンで刻み幅どおりに値が増減すること。周波数の順序が逆転しないこと
4. ゲイン、MBC、入力ゲイン、歪み緩和、Fading深度・周期を動かし、値表示とエンジンdetailの反映が変わること。調整中もsession `0` のDynamicsProcessingが維持されること
5. 「初期値へ戻す」で選択中プリセットの値へ戻ること。別プリセットへ切り替えて戻ったときは、同一起動中の調整値がプリセット単位で保持されること
6. force-stop後の再起動で調整値が初期値へ戻ること（永続化していないこと）
7. 操作後のfiltered `logcat` に `FATAL EXCEPTION`、`ANR in`、アプリ由来の未処理Exceptionがないこと

2026-08-30 の実機確認:

- workflow `fd74255730a073f1c0512f3c3801348d` で `:app:testDebugUnitTest` / `:app:assembleDebug` をPASS。APK SHA-256は `DECA69FDC4C1ACDAD86911DF518F0BEFDFDB31B52E9F8EDDE0F437AD47EBFE24`
- Pixel 10 Pro（Android 17 / serial `56101FDCH006CX` / Pixel Buds Pro 2 Bluetooth A2DP）へ最終APKをinstallし、`Narrow AM` の調整パネルを開いた。全4周波数スライダーとゲイン／MBC／入力／歪み緩和／Fadingの各スライダー、`−` / `＋`ボタン、リセットボタンをUI treeで確認した
- 低域カット開始を`300 Hz → 310 Hz → 300 Hz`（`＋` / `−`）へ戻し、スライダーでは`330 Hz`へ変更できた。入力ゲインは`0.0 dB → 3.0 dB`へ移動後、リセットで`0.0 dB`へ復帰した。長めのスライダー操作でもUIは`状態: Active`、Equalizerは`Not used (backend=dynamics_only)`を維持し、session `0` のDynamicsProcessing effectが外れなかった
- `dumpsys activity services dev.hondasports.razio` でeffect用FGS `isForeground=true`を確認し、操作後のfiltered logcatにRAZIO由来のcrash / ANRはなかった。これは操作・構造確認であり、各値の最終的な聴感プリセット決定はユーザー確認待ち

### 入出力スペクトラムアナライザー検証PoC

この機能は、音声を再生し直さずに入力・出力の傾向を比較するための観測tapです。入力は `AudioPlaybackCapture` → `AudioRecord`、出力は `Visualizer(session 0)`。どちらも同じ1024点FFTで10帯域へ変換し、`Active` / `Partial` / `Error` として取得可否を表示します。Visualizerは厳密なpost-DSP PCMではないため、プリセットの最終判定はDynamicsProcessingのreadbackと聴感で行います。

必須確認:

1. `:app:testDebugUnitTest` と `:app:assembleDebug` を `gradle-run` で通し、debug APKをPixelへinstallする
2. Spotifyなど対象アプリを再生し、RAZIOの「解析を開始」を押す。`RECORD_AUDIO`許可後、MediaProjectionの画面共有同意を通す（Android 17では音声取得を要求するUIになる）
3. UIが `Active（入力・出力）` になり、入力／出力の棒グラフ、RMS、Peakが更新されることを確認する。detailが `入力tap=AudioPlaybackCapture` / `出力mix tap=Visualizer(session 0)` / `前後位置は端末依存` であることを記録する
4. `dumpsys media_session`で対象アプリが`PLAYING`、`dumpsys activity services dev.hondasports.razio`でProjection型FGSがforegroundであることを確認する
5. 入力PCMをAudioTrackへ再生していないため、解析開始前後で二重再生・意図しない音量二重化がないことを聴感確認する
6. 「解析を停止」を押し、UIが`Stopped`、`dumpsys media_projection`が`null`になることを確認する。RAZIOの電源がONならeffect用specialUse FGSだけが残ることを確認する
7. 対象アプリのcapture policyやProjection拒否を再現できる場合、`Partial` / `Error`と理由が表示され、元音声を抑制しないことを確認する

2026-08-30 の実機結果:

- Pixel 10 Pro（Android 17 / Pixel Buds Pro 2 Bluetooth A2DP）でSpotify `PlaybackState=PLAYING`を再生し、画面共有のアプリ選択でSpotifyを指定した。UIが`Active（入力・出力）`となり、両グラフのフレームが更新された（最終再確認時の一時値: 入力RMS約`-9.9 dB`、出力RMS`0.0 dB`。曲・音量・フレームに依存）
- `dumpsys activity services`でProjection型FGS `isForeground=true`、停止後に`dumpsys media_projection`が`null`、Spotify再生継続を確認した。アプリのcrash / ANRはなし
- `:app:testDebugUnitTest` / `:app:assembleDebug` はworkflow `5a5253f24ae29370dc7f3a53472a2221`でPASS。入力の音声を再生し直さないため、二重再生を作らない構造をコードとUI detailで確認した

### Hiss / Crackle AudioTrack overlay PoC

元音声を `AudioPlaybackCapture` でコピーして再生するのではなく、RAZIO生成ノイズだけを `AudioTrack` で同時再生する検証。PoCの実装・測定条件は `docs/audio-research.md` の計画に合わせる。

必須確認:

1. 画面表示中にPoCを開始し、既存のRazioAudioServiceをforegroundにしたままSpotify / YouTube / radikoを再生
2. 本体スピーカーとSoundCore 2 Bluetoothで、対象アプリがpause・意図しないduckを起こさずノイズが聞こえること
3. RAZIO OFF、route切替、Home、画面OFF、対象アプリpauseでノイズが止まり、残留Runnable / AudioTrackがないこと
4. `dumpsys media_session` / `dumpsys audio` / `dumpsys activity services dev.hondasports.razio` / `dumpsys media.audio_flinger` と `AudioHardening`・crash・ANRのfiltered logcatを保存
5. 元音声を捕捉して再再生しないため、sourceの二重再生や捕捉許可によるアプリ差をPoCの合否から分離して記録

2026-08-30 の実装・構造確認結果:

- `NoiseOverlayController` が決定論的なPCMを生成し、`USAGE_MEDIA` / `CONTENT_TYPE_UNKNOWN`・AudioFocusなしの独立 `AudioTrack` で再生する。AudioPlaybackCaptureは使わず、元音声のミュート／差し替えもしない。RAZIO OFFではノイズスイッチをクリアしてAudioTrackとFGSを停止し、route changeでは停止・再生成する
- `:app:testDebugUnitTest` と `:app:assembleDebug` は workflow `2874600e19b809682ef98d192f8cbe31` でPASS。最新版検証APKのSHA-256は `678780EA7C35DF1ED3F99ED3A4A30B56B7696669B340FD54BAAE95C10DCDC9AC`
- Pixel 10 Pro（`blazer`、Android 17 `CP2A.260805.005`、serial `56101FDCH006CX`）でSpotify再生中にHiss / Crackleを有効化し、`dumpsys audio`でSpotifyとRAZIOの独立AudioTrackが同時に `started` になることを確認した。RAZIOはmono / 48 kHz、detailは `usage=media content=unknown focus=none`
- Home・画面OFF（`Dozing`）でも既存のspecialUse FGSとRAZIO AudioTrackが維持された。RAZIO OFFではUIが `Disabled` / `Idle`、FGSとRAZIO AudioTrackが消えることを確認した
- Bluetooth切断で出力先がspeaker（device id `3`）へ移り、再接続でPixel Buds Pro 2（device id `7241`）へ戻った。route logには `noise overlay stopped reason=route_change` と再開を確認した。再接続時に既存DynamicsProcessingのroute readbackが `UnsupportedOperationException: AudioEffect: invalid parameter` になる警告が1件出たが、既存fallbackでeffectを再初期化し、ノイズAudioTrackは維持された
- Home・画面OFF・route切替のlogcatをクリアして確認した範囲では、新規の `AudioHardening`、アプリcrash、ANRはなかった。ただし過去のプロセス更新時にAndroid 17の `AudioHardening` ログが出た履歴はあり、長時間のバックグラウンド聴感は別途確認する
- **聴感受入済み（ユーザー「OK」）**。`/sdcard/Download/razio-silence-10s.wav`（48 kHz / mono）をVLCで再生し、ループ設定なしの短時間試行でHiss / Crackleが無音ベースへ重なって聞こえることを確認した。VLC側のループ再生と長時間バックグラウンド聴感は未検証だが、独立ノイズAudioTrackの出力確認というPoC目的は達成したため、製品採用・commitへ進む

### DynamicsProcessing単独経路

現行経路は `DynamicsProcessing` 1つだけ（Pre-EQ flat → MBC → Post-EQ → Limiter）とする。通常の `Equalizer` は生成せず、UIのEqualizer欄は `Not used (backend=dynamics_only)` になる。Post-EQの最終bandは20 kHzまで持ち、全プリセットの高域目標は `-48 dB` とする。

1. Spotifyを再生し、本体スピーカー / Pixel Buds Pro 2 Bluetooth A2DPでRAZIOをONにする
2. UI detailで `Equalizer: Not used`、`preEq=flat`、`postEq=curve`、`postEqBands` の20 kHz bandが確認できることを記録する
3. UI detailのPost-EQ 9 / 18 / 20 kHzが`-48dB`、stageが`inUse` / `enabled`、9 bandが全て有効であることをreadbackする。満たさない端末は`Ready` / `Active`合格にしない
4. Narrow AM / Vintage speaker / Weak signal / Saturation / Fadingを順に選択し、10 kHz付近の高域残り、低域・声域のバランス、音量低下、過度な歪みを聴感メモへ残す
5. ON / OFF、プリセット切替、route change、Home、画面OFF、force-stop後の復元を確認する。プリセット切替は約80 msで途切れず、effectを二重生成しないことを確認する
6. `dumpsys media.audio_flinger`、`dumpsys activity services dev.hondasports.razio`、`dumpsys audio`、filtered `logcat`でeffect数（1）、FGS、route、crash/ANRを保存する

合否は、Pixelの両出力先・SpotifyでDynamicsProcessing 1 effectが安定し、`-48 dB`の高域readback、音量低下・クリック・過度な歪みがないこと。DynamicsProcessingが利用できない端末は `Unsupported` / `Error` として記録し、Equalizerへ戻ったことを合格扱いにしない。

2026-08-29 のA/B構造確認（旧PoC）では、Pixel 10 Pro / Android 17 / SoundCore 2 / SpotifyでAはsession `0` の2 effects、BはDynamicsProcessing 1 effectとなり、UI detail・FGS・切替時のrelease / recreate / enableを確認した。旧BのPre-EQは4.5kHzより上を処理せず、MBC後段makeup gainが低域カットを戻し得たため、BをPost-EQ（20kHzまで）へ修正した。その後のユーザー判断でBを採用し、AのSplit切替は削除した。過去のA/B readbackは履歴として保持し、現行の合否は下記の単独経路確認で判定する。
続く過去の調整では、全プリセットの低域・高域目標、MBC、Post-EQ上限を段階的に再調整した。詳細なworkflow・APK hash・旧Splitのreadbackは履歴として残すが、現行コードが参照するのはDynamicsProcessing単独のPost-EQカーブだけである。

2026-08-30 の現行経路確認では、workflow `03f704c0c89ce0dc3fd3a9daeeae36e9` のunit test / debug APK buildをPASS（最終APK SHA-256 `116EC8C867447EB6CCE2E2A75AA1431A9229D0307CC875067609B71AF4FAF19D`）。Pixel 10 Pro（Android 17 / Pixel Buds Pro 2 / Spotify）で5プリセットを切替え、UIの `Equalizer: Not used`、`preEq=flat`、`postEq=curve`、各Post-EQの9 / 18 / 20 kHz `-48dB`をreadbackした。生成・再利用時のPost-EQ guard（stage有効、9 band、全band有効、高域ゲイン、20 kHz終端）も通過した。`dumpsys media.audio_flinger` はsession `0` のDynamicsProcessing 1 effectのみ、ON/OFFは `Active` / `Disabled`、FGSは `isForeground=true`、Spotifyは `PLAYING` を維持した。Bluetooth disable / enableによるroute change後も同じ1 effectとenable状態を確認し、RAZIO由来のcrash / ANR / `AudioHardening`はなかった。10 kHz付近の聴感受入はユーザー確認待ち。

## テスト不能時

実機テストが必要なのに端末が接続されていない場合、テスト済みとは扱いません。commit して完了にもしません。

残すこと:

- 自動テスト: 実施済み / 未実施
- build: 成功 / 失敗
- 実機テスト: 実施済み / 未実施
- 未実施理由
- 次に必要な具体的手順

「実行できなかったので問題なしとして進める」という判断は禁止します。
