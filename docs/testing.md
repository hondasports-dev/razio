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
7. ON のまま Bluetooth 接続 / 切断し、効果が残るか（Pixel 10 Pro で確認済み。`audio devices removed/added`、`route change wantOn=true`、EQ / Dynamics `actual=true`、再接続後 session 0 の 2 effects）
8. ON のままプリセットを切り替え、UI の選択状態・session 0 の EQ detail・聴感が切り替わること。切替中に `2 effects for session 0` が維持され、音量差が許容範囲であること
9. Narrow AM → Vintage speaker → Weak signal → Saturation → Fading を短時間に連続選択し、旧プリセットへ瞬間的に戻る音色ジャンプや素通り区間がないこと

2026-08-29 の初回プリセット調整では、Pixel 10 Pro（Android 17）/ SoundCore 2 / Spotify で上記 8・9 を実施し、ユーザー聴感も受入済み。Saturationの入力ゲイン・強い圧縮の聴感もユーザー確認済み。続く全プリセット両端カット再調整（Narrow AM / Vintage speaker / Weak signal / Saturation / Fading）も同じ実機でユーザー受入済み。詳細な EQ 値・`dumpsys`・logcat は `docs/audio-research.md` に記録しています。

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

### DynamicsProcessing 単体 A/B PoC

実施時期は、現行の全プリセット調整を実機聴感で受入れた直後、Hiss / Crackle の AudioTrack オーバーレイ実装へ進む前とする。実装済みの「処理方式」から、既定経路を切り替えず同じAPK内でA/Bを切り替える。切替時はeffectを再生成するため短い再初期化が入り、ON中なら新しいchainを再enableする。選択は永続化しない。

1. A（現行）の `Split`（Equalizer + DynamicsProcessing / Pre-EQ flat）で、Spotifyを再生し本体スピーカー / SoundCore 2を確認
2. B（候補）の `Dynamics only`（Equalizer は `Not used`、DynamicsProcessing単体の MBC + Post-EQ + Limiter）で同じ素材・同じ音量を確認。UI detailの `postEq=curve` と `postEqBands` で最終EQのcutoff/gainを記録する
3. Narrow AM / Vintage speaker / Weak signal / Saturation / Fadingを順に比較し、低域・高域のカット、声域の明瞭度、音量差、Compression / Limiterの副作用を記録
4. ON / OFF、プリセット切替、route change、Home、画面OFF、force-stop後の復元を確認
5. `dumpsys media.audio_flinger`、`dumpsys activity services dev.hondasports.razio`、`dumpsys audio`、filtered `logcat`でeffect数・FGS・route・crash/ANRを保存

合否は、BがAより明確に有利で、Pixelの両出力先・Spotifyで安定し、音量低下・クリック・過度な歪みがないこと。差が小さい、または端末依存の失敗がある場合はAを既定として維持する。

2026-08-29 の構造確認では、Pixel 10 Pro / Android 17 / SoundCore 2 / Spotify で A は session `0` の2 effects、B は DynamicsProcessing 1 effectとなり、UI detail・FGS・切替時の release / recreate / enable を確認した。旧BのPre-EQは4.5kHzより上を処理せず、MBC後段makeup gainが低域カットを戻し得たため、BをPost-EQ（20kHzまで）へ修正した。修正版は同端末の Pixel Buds Pro 2 Bluetooth A2DP / Spotify でも `postEq=curve` と各帯域のreadback、1 effect構成を再確認済み。歪み報告を受け、Dynamics only はプリセット別に Narrow/Fading `1.2:1`・post `0dB`、Vintage `1.5:1`・post `+2dB`、Weak `4:1`・post `+9dB`、Saturation `8:1`・input `+6dB`・post `0dB`、共通 attack/release `20/230ms`・knee `12dB` へ再調整した。Post-EQの正のブーストは `+2dB` を上限とし、常時Limiter動作と歪みを抑える。再調整後のworkflow `ac68d98fa5f4f1af7789745ef519b477`でunit test / debug APK buildをPASSし、同端末・Pixel Buds Pro 2・Spotifyでnative readback、1 effect構成、FGS、再生継続、crash/ANRなしを再確認済み。音質の最終受入はユーザー聴感待ち。
続く第2段では、歪みを増やさないためMBC設定を維持したまま、全プリセットの低域・高域ゲイン目標を約6dB深くした。workflow `072fac01ea2f8d798c12426264509477` のunit test / debug APK buildをPASSし、同端末のDynamics onlyで`postEqBands`の端部readback、Spotify再生継続、session `0` の1 effect、FGS、crash/ANRなしを確認済み。その後の聴感報告（Narrow AM/Fadingの歪み、Weak signalの音量不足、Saturationの高域残り）を受け、Dynamics onlyのMBCを Narrow/Fading `1.2:1`・post `0dB`、Vintage `1.5:1`・post `+2dB`、Weak `4:1`・post `+9dB`、Saturation `8:1`・input `+6dB`・post `0dB` へ再調整し、Post-EQブーストを `+2dB` に制限した。最新workflow `ac68d98fa5f4f1af7789745ef519b477` のunit test / debug APK buildをPASSし、同端末・Pixel Buds Pro 2・Spotifyで各プリセットのnative readback、session `0` の1 effect、FGS、再生継続、crash/ANRなしを再確認済み。続く非SaturationのSplit共通穏和化もworkflow `0ff2a8730778cdae1cdb1687ee20dac3` でunit test / debug APK buildをPASSし、同端末でSplit/Dynamics only双方のプリセットratio/post、Post-EQ `+2dB`上限、再生継続、session `0`、FGS、crash/ANRなしを確認した。今回の中域・高域再調整はworkflow `c37ca4a87ccc623ea4aca984cd933b83` の `:app:testDebugUnitTest` / `:app:assembleDebug` をPASSし、SplitではVintage/Weakの中域 `+5dB`、Dynamics onlyでは全プリセットの高域 `-40dB` と中域最大 `+3dB` をnative/UI readbackで確認済み。APK SHA-256は `E9A13DAEAFEE3651D57A36C3391469E8BAFAE26F637CE3B45CD43D103434954D`。実機はPixel 10 Pro / Android 17 / Pixel Buds Pro 2 Bluetooth A2DPで、FGS、メディア再生継続、session `0` のeffect数、crash/ANRなしを確認し、低高域バランスと歪み・音量の最終受入はユーザー聴感待ち。

## テスト不能時

実機テストが必要なのに端末が接続されていない場合、テスト済みとは扱いません。commit して完了にもしません。

残すこと:

- 自動テスト: 実施済み / 未実施
- build: 成功 / 失敗
- 実機テスト: 実施済み / 未実施
- 未実施理由
- 次に必要な具体的手順

「実行できなかったので問題なしとして進める」という判断は禁止します。
