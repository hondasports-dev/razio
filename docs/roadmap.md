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

## Phase 1B: AudioPlaybackCapture PoC

Phase 1 が Red の場合だけ実施します。

- [ ] MediaProjection permission flow
- [ ] AudioPlaybackCaptureConfiguration
- [ ] AudioRecord
- [ ] PCM passthrough
- [ ] custom band-pass DSP
- [ ] AudioTrack playback
- [ ] latency 測定
- [ ] 元音声との二重再生確認
- [ ] capture 不可アプリの挙動確認

採用条件:

- 元音声との競合を含め、日常利用できる UX が成立すること
- global AudioEffect より明確な価値があること

成立しなければ、通常 APK の制約としてプロジェクト仕様を見直します。root 化へ自動的には進みません。

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
- [x] 入出力スペクトラム検証（AudioPlaybackCapture / AudioRecord入力tap + session `0` Visualizer出力tap + FFTグラフ。元音声を再生し直さない。Pixel 10 Pro / Android 17で`Active`・停止・Projection解放を確認）

## Phase 3: Sound design

基本動作が安定した後に音の個性を作ります。

候補:

- [x] Narrow AM（AM実用帯域・声域重視へ再調整。Pixel 10 Pro / SoundCore 2で実機聴感確認済み）
- [x] Vintage speaker（70〜80年代ラジカセのかまぼこ型へ再調整。Pixel 10 Pro / SoundCore 2で実機聴感確認済み）
- [x] Weak signal（高域カットと音量補正を追加。Pixel 10 Pro / SoundCore 2で実機聴感確認済み）
- [x] Saturation（入力ゲイン＋強いMBCによる飽和近似。Pixel 10 Pro / SoundCore 2で実機聴感確認済み）
- [x] 全プリセット両端カット再調整（低域・高域のロールオフを強化。Pixel 10 Pro / SoundCore 2 / Spotifyでユーザー聴感受入済み）
- [ ] 両端カット第2段（全プリセットの低域・高域目標をさらに約6dB深く調整。Pixel 10 Pro / Pixel Buds Pro 2で構造確認済み、最終聴感受入待ち）
- [ ] 高域カット第3段（DynamicsProcessing単独化に合わせ、全プリセットの10 kHz付近を `-40dB` から `-48dB` へ変更。Pixel実機readback確認済み、聴感受入待ち）
- [x] プリセット値の試聴調整（周波数・ゲイン・MBC・歪み緩和・FadingをComposeスライダーで変更。周波数は`−` / `＋`でも増減し、DynamicsProcessingへ約80 msで反映。値は起動中のみ保持）
- [x] 周波数カーブ可視化（選択中プリセットのゲインカーブ、カット／中域帯の網掛け、6境界線、20 Hz〜20 kHzの細分化対数グリッド）
- [x] 周波数境界の細分化（低域・高域に中間境界／中間ゲインを追加し、カット傾斜を二段階で調整）
- [x] Hiss（global AudioEffectでは生成不可。AudioTrack独立ノイズオーバーレイPoCを実装し、Pixel 10 Proで無音ベースへの重畳を聴感確認済み）
- [x] Crackle（global AudioEffectでは生成不可。AudioTrack独立ノイズオーバーレイPoCを実装し、Pixel 10 Proで無音ベースへの重畳を聴感確認済み）
- [x] Fading（DynamicsProcessing の input gain をゆっくり変動。Pixel 10 Pro / SoundCore 2 / Spotifyでユーザー聴感受入済み）
- [ ] Mono 感の強化（DynamicsProcessing / Equalizerに左右混合APIがなく、session `0` での確実なモノラル化は不可。AudioPlaybackCaptureまたは自前再生の別PoCまで保留。詳細は `docs/audio-research.md`）

AudioEffect API だけで困難な項目は backend の制約を確認してから実装します。

## Phase 4: Product UI

- [ ] レトロラジオ風デザイン（暖色light/darkテーマ、パネル分割、プリセット横スクロールの初回実装・Pixel 10 Pro表示確認済み。最終デザイン受入待ち）
- [x] tuning dial の表現（調整パネルに6境界の帯域位置をラジオ目盛り風に表示。操作は下のスライダーで行う）
- [ ] signal meter（製品向け。検証用スペクトラムはPhase 2に実装済みだが、聴感を保証するメーターではない）
- [x] preset UI（Narrow AM / Vintage speaker / Weak signal / Saturation / Fading）
- [ ] dark / light 方針
- [ ] icon / branding

UI が audio backend の仕様を隠さないようにします。

## Phase 5: Automation

- [ ] GitHub Actions
- [ ] unit test
- [ ] lint
- [ ] assembleDebug
- [ ] artifact APK
- [ ] AI エージェント用実装ループ改善

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
