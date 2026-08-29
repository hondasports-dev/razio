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
- Narrow AM が250 Hz以下／3.4 kHz以上を抑えた狭いAM放送風、Vintage speaker が350 Hz〜3 kHzのかまぼこ型として聞こえるか
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

2026-08-29 の最終プリセット調整では、Pixel 10 Pro（Android 17）/ SoundCore 2 / Spotify で上記 8・9 を実施し、ユーザー聴感も受入済み。Saturationの入力ゲイン・強い圧縮の聴感もユーザー確認済み。詳細な EQ 値・`dumpsys`・logcat は `docs/audio-research.md` に記録しています。

### Hiss / Crackle AudioTrack overlay PoC（未実装）

元音声を `AudioPlaybackCapture` でコピーして再生するのではなく、RAZIO生成ノイズだけを `AudioTrack` で同時再生する検証。PoCの実装・測定条件は `docs/audio-research.md` の計画に合わせる。

必須確認:

1. 画面表示中にPoCを開始し、既存のRazioAudioServiceをforegroundにしたままSpotify / YouTube / radikoを再生
2. 本体スピーカーとSoundCore 2 Bluetoothで、対象アプリがpause・意図しないduckを起こさずノイズが聞こえること
3. RAZIO OFF、route切替、Home、画面OFF、対象アプリpauseでノイズが止まり、残留Runnable / AudioTrackがないこと
4. `dumpsys media_session` / `dumpsys audio` / `dumpsys activity services dev.hondasports.razio` / `dumpsys media.audio_flinger` と `AudioHardening`・crash・ANRのfiltered logcatを保存
5. 元音声を捕捉して再再生しないため、sourceの二重再生や捕捉許可によるアプリ差をPoCの合否から分離して記録

## テスト不能時

実機テストが必要なのに端末が接続されていない場合、テスト済みとは扱いません。commit して完了にもしません。

残すこと:

- 自動テスト: 実施済み / 未実施
- build: 成功 / 失敗
- 実機テスト: 実施済み / 未実施
- 未実施理由
- 次に必要な具体的手順

「実行できなかったので問題なしとして進める」という判断は禁止します。
