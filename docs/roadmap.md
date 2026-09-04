# Roadmap

## Phase 0: Repository bootstrap

目的: AI / 人間のどちらでも実装開始できる状態にする。

- [x] Android プロジェクト作成
- [x] Kotlin + Jetpack Compose
- [x] Gradle Wrapper
- [x] package name 決定（`dev.hondasports.razio`）
- [x] unit test が実行可能
- [x] debug APK が build 可能
- [x] CI の最小構成

完了条件:

```bash
./gradlew test
./gradlew assembleDebug
```

が成功すること。

## Phase 1: Global AudioEffect PoC

最重要フェーズ。

実装:

- [x] session `0` で Equalizer を生成（旧Split PoC。現行経路では生成しない）
- [x] session `0` で DynamicsProcessing を検証
- [x] enable / disable
- [x] effect state を画面に表示
- [x] Normal AM の仮プリセット
- [x] logcat diagnostics

実機確認:

- [x] YouTube（Pixel 10 Pro。効果あり）
- [x] 音楽アプリ（Pixel 10 Pro。アプリ名未記録）
- [x] Chrome（Pixel 10 Pro）
- [x] 本体スピーカー（Pixel 10 Pro）
- [x] Bluetooth（Pixel 10 Pro。イヤホン機種未記録）

2026-08-29 Pixel 10 Pro / Android 17: YouTube / 音楽アプリ / Chrome がスピーカーと Bluetooth で効果あり。記録は `docs/audio-research.md`。この端末では Phase 1 を **Green** とし、Global AudioEffect を MVP とする。

判断:

### Green（この端末）

主要ケースで global effect が成立する。

→ Phase 2 へ。

### Yellow

一部条件のみ成立する。

→ 対応条件を明文化し、複数端末で再検証する。

### Red

他アプリ音声への効果が実用上成立しない。

→ Phase 1B の AudioPlaybackCapture PoC へ。

## Phase 1B: AudioPlaybackCapture PoC（Mono代替経路の評価）

Phase 1 が Red の場合だけ実施します。

- [x] MediaProjection permission flow（Pixel 10 Pro / Android 17で音声同意→FGS→Projection取得）
- [x] AudioPlaybackCaptureConfiguration（MEDIA / GAME / UNKNOWN usageに一致する全体ミックス。アプリUID絞り込みは未対応）
- [x] AudioRecord（stereo優先、mono fallbackはPartial表示）
- [x] PCM passthrough / mixdown（2ch入力を`(L + R) / 2`でstereoへ複製。左右独立信号の相関検証は未実施）
- [ ] custom band-pass DSP（製品音色の適用は未着手。Mono差し替えbackendが不採用のため現行製品では対象外）
- [x] AudioTrack playback（capture policy=NONE、AudioFocusなし）
- [ ] latency 測定（現在はbuffer合計による概算約170–506 msのみ。end-to-end timestampは未測定。Mono差し替えbackendが不採用のため現行製品では対象外）
- [x] 元音声との二重再生確認（対象TrackとRAZIO Trackの同時`started` / `active`という構造を確認。元音声ミュートは不可）
- [x] capture 不可アプリの挙動確認（radikoで無音PCMを検出し、`Partial` と理由を表示。再生中の一時停止でも同じ状態へ遷移し、再開で`Active`へ復帰）

採用条件:

- 元音声との競合を含め、日常利用できる UX が成立すること
- global AudioEffect より明確な価値があること

- [x] Mono製品採用判断（2026-08-31、Pixel 10 Proでユーザー聴感上の二重化を確認。元音声を抑制できない通常アプリの制約によりNo-go／不採用。PoCは検証証跡用の明示起動に限定）

成立しなければ、通常 APK の制約としてプロジェクト仕様を見直します。root 化へ自動的には進みません。

Phase 1Bの未完了項目は、Phase 1をGreenとしてglobal AudioEffectをMVPに採用し、Mono差し替えbackendをNo-goとした判断により、現行製品の実装対象外として保留します。再開する場合は、元音声を抑制できる差し替え経路が実機で成立することを先に確認します。

## Phase 2: MVP

Phase 1 で採用する audio backend が決定した後に進みます。

- [x] RAZIO ON / OFF
- [x] Normal AM preset
- [x] effect availability 表示
- [x] 設定保存（ON/OFF を DataStore に保存し、起動時に復元）
- [x] lifecycle 対応（Application 生存。ON 中は specialUse FGS。プロセス死は起動時に付け直し）
- [x] route change 対応（AudioDeviceCallback で preset 付け直し / 再生成）
- [x] エラーハンドリング（Unsupported / Error を画面表示）
- [x] 最小 Compose UI
- [x] 実機 regression 手順（起動時 ON 復元・FGS 通知・Home 放置・画面 OFF 後の effect 維持・Bluetooth route 再接続は Pixel 10 Pro で確認。詳細は `docs/audio-research.md` / `docs/testing.md`）
- [x] DynamicsProcessing 単体 A/B PoC（B案を採用。Equalizerを生成せず、Post-EQ/MBC/Limiterを1 effectへ集約。Pixel 10 Proでsession `0` の1 effect構成を確認）
- [x] 入出力スペクトラム検証（AudioPlaybackCapture / AudioRecordをエフェクト前入力tapとして使い、DynamicsProcessingプロファイルを同一フレームへ反映したエフェクト後推定出力をFFT表示。入力不能時のみsession `0` Visualizerをfallback。元音声を再生し直さない。Pixel 10 Pro / Android 17で`Active`・停止・Projection解放を確認）

## Phase 3: Sound design

基本動作が安定した後に音の個性を作ります。

候補:

- [x] Narrow AM（AM実用帯域・声域重視へ再調整。Pixel 10 Pro / SoundCore 2で実機聴感確認済み）
- [x] Vintage speaker（70〜80年代ラジカセのかまぼこ型へ再調整。Pixel 10 Pro / SoundCore 2で実機聴感確認済み）
- [x] Weak signal（高域カットと音量補正を追加。Pixel 10 Pro / SoundCore 2で実機聴感確認済み）
- [x] Saturation（入力ゲイン＋強いMBCによる飽和近似。Pixel 10 Pro / SoundCore 2で実機聴感確認済み）
- [x] 全プリセット両端カット再調整（低域・高域のロールオフを強化。Pixel 10 Pro / SoundCore 2 / Spotifyでユーザー聴感受入済み）
- [x] 両端カット第2段（全プリセットの低域・高域目標をさらに約6dB深く調整。Pixel 10 Pro / Pixel Buds Pro 2で構造確認、ユーザー聴感受入済み）
- [x] 高域カット第3段（DynamicsProcessing単独化に合わせ、全プリセットの10 kHz付近を `-40dB` から `-48dB` へ変更。Pixel実機readback、ユーザー聴感受入済み）
- [x] プリセット値の試聴調整（周波数・ゲイン・MBC・歪み緩和・FadingをComposeスライダーで変更。周波数は`−` / `＋`でも増減し、DynamicsProcessingへ約80 msで反映。値は起動中のみ保持）
- [x] 周波数カーブ可視化（選択中プリセットのゲインカーブ、カット／中域帯の網掛け、6境界線、20 Hz〜20 kHzの細分化対数グリッド）
- [x] 周波数境界の細分化（低域・高域に中間境界／中間ゲインを追加し、カット傾斜を二段階で調整）
- [x] Hiss（global AudioEffectでは生成不可。AudioTrack独立ノイズオーバーレイPoCを実装し、Pixel 10 Proで無音ベースへの重畳を聴感確認済み）
- [x] Crackle（global AudioEffectでは生成不可。AudioTrack独立ノイズオーバーレイPoCを実装し、Pixel 10 Proで無音ベースへの重畳を聴感確認済み）
- [x] Hiss / Crackle ゲイン調整（第一面の ‹ › とセグメント帯で -24〜+12 dB。1 dB刻み、基準振幅 0 dB、内部基準 Hiss `0.04` / Crackle `0.20`）
- [x] Fading（DynamicsProcessing の input gain をゆっくり変動。Pixel 10 Pro / SoundCore 2 / Spotifyでユーザー聴感受入済み）
- [x] Shortwave（弱い短波受信。500Hz〜1.25kHz、±6dB / 2.4秒の揺れ）
- [x] プリセット調整とノイズレベルの永続化（DataStore。再起動後も復元）
- [x] Hiss / Crackle を第一面操作にする（スイッチ＋プリセットと同じ ‹ › / セグメント帯。詳細は状態観測）
- [x] Mono 感の強化は不採用（DynamicsProcessing / Equalizerに左右混合APIはなく、別経路PoCのcapture→downmix→AudioTrackは元音声との二重化をユーザー聴感で確認。製品コード・UI・testは削除し、詳細は `docs/audio-research.md` の履歴へ残す）

AudioEffect API だけで困難な項目は backend の制約を確認してから実装します。

## Phase 4: Product UI

- [x] 製品UI（第一面は大きな RAZIO、円形電源、LIVE/STANDBY、プリセット ‹ ›、塗りカーブ、カーオーディオ風スペアナ。設定チップと6スライダーは出さない）
- [x] tuning dial の表現（詳細パネルに6境界の帯域位置をラジオ目盛り風に表示。操作は下のスライダーで行う）
- [x] signal meter（第一面は1/6オクターブ49帯域のカーオーディオ風LEDスペアナ。解析前はプリセット形のデモ点灯、SIG / 装飾局周波数 / CLIP。詳細の検証用2本グラフは削除）
- [x] preset UI（Narrow AM / Vintage speaker / Weak signal / Saturation / Fading / Shortwave を ‹ › ・点・横スワイプで切替）
- [x] dark / light 方針（system dark modeに追従する暖色light / dark scheme。dynamic colorは既定OFF）
- [x] icon / branding（ベークライト筐体・紙面パネル・琥珀色の同調部品を描いたRAZIO adaptive icon。API 21〜25はvector layer-listへフォールバック）
- [x] 詳細調整UI（`詳細設定を開く` で6周波数境界・同調ダイヤル・ゲイン・Dynamics・Character値とNoise状態 / Engine検証パネルを展開。Hiss / Crackle操作は第一面。プリセット名直下のリセットは常時表示し、定義値から外れたときだけ活性）

UI が audio backend の仕様を隠さないようにします。

## Phase 5: Automation

- [x] GitHub Actions（`.github/workflows/ci.yml` で Android SDK準備・Gradle cache・検証ジョブを定義）
- [x] unit test（CIで `./gradlew test` を実行）
- [x] lint（CIで `./gradlew lint` を実行）
- [x] assembleDebug（CIで `./gradlew assembleDebug` を実行）
- [x] artifact APK（CIで `app-debug.apk` を `razio-debug-apk` としてupload）
- [x] AI エージェント用実装ループ改善（Windowsの`python3`実体確認と`py -3` fallbackをLoop正本へ明記し、Gradle検証の無駄な再試行を防止）

ADB 実機試験はローカル環境を基本とし、必要になった場合に device farm を検討します。

## 優先順位の原則

```text
音声経路の成立性
    > 安定性
    > テスト可能性
    > AM らしさ
    > UI の作り込み
```

RAZIO では、見た目が完成していても他アプリ音声に効果が掛からなければプロダクトのコア要件を満たしません。
