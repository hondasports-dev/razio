# RAZIO

RAZIO は、Android 端末で再生される音声を昔の AM ラジオのような音質に変換して楽しむことを目的とした個人向け Android アプリです。

> 現代の音を、昔の電波へ。

## 目的

RAZIO は、YouTube、音楽アプリ、ゲーム、ブラウザなどの再生音を、可能な範囲で端末全体に対して AM ラジオ風へ変換する体験を提供します。

本プロジェクトでは **root 化を前提にしません**。Android の公開 API と通常アプリで利用可能な仕組みを優先し、端末依存の挙動は実機検証で判断します。

## 技術方針

- Kotlin
- Jetpack Compose
- Gradle
- Android AudioEffect API
- DynamicsProcessing（Post-EQ / MBC / Limiter）
- プリセット調整用Composeスライダー（周波数の− / ＋ボタン付き。DataStoreへ保存）
- 選択中プリセットの周波数カーブプレビュー（カット帯の網掛け・境界線・細分化した対数軸）
- 第一面の1/6オクターブ（49帯域）カーオーディオ風LEDスペアナ（AudioPlaybackCaptureの推定出力 / FFT。詳細の検証用2本グラフは置かない）
- ADB による実機検証
- Codex / AI エージェントによる実装・テストループ

## 最初に検証すること

最優先の PoC は、audio session `0` に対する global AudioEffect が対象端末で実際に機能するかの確認です。

想定する AM ラジオ風処理:

- Mono 化（通常アプリでは元音声を抑制できず二重化するため不採用。関連PoCコードは削除済み）
- 低域を強く抑え、3.0 kHz 以上を減衰
- 550 Hz〜2.2 kHz の声域を強調
- Compression
- Limiter

`Saturation` は、入力ゲインを少し上げて強い Compression / Limiterへ押し込み、公開AudioEffectだけで可能な範囲の飽和感を近似します。波形シェーパーによる倍音歪みは対象外です。

`Fading` は、Narrow AM の狭い帯域（300 Hz 未満 / 3.0 kHz 以上を強くカット）を保ったまま入力ゲインをゆっくり揺らし、受信状態が変動するようなフェージング感を近似します。

`Shortwave` は帯域をさらに狭く（500 Hz 未満 / 1.25 kHz 以上を強くカット）し、入力ゲインをより大きく・速く揺らして遠い短波受信を近似します。

`Vintage speaker` では、70〜80年代ラジカセを想定して 180 Hz 未満を抑え、
4.0 kHz から高域を丸めつつ 450 Hz〜2.6 kHz をかまぼこ型に残します。

この方式は Android では deprecated な領域を含み、端末・OS・Audio HAL の実装によって動作が異なる可能性があります。そのため、実装より先に小さな PoC で成立性を検証します。

プリセットの効き具合の傾向は、第一面のLEDスペアナで確認できます。解析開始はスペアナ本体のタップです。入力は `AudioPlaybackCapture` で再生ミックスをコピーした「エフェクト前」の基準フレームで、第一面へ出す出力は同じフレームへ現在の `DynamicsProcessing` プロファイル（Post-EQ / MBC / Limiterの公開パラメータ）を反映した「エフェクト後・推定」です。Androidの公開APIではglobal effect直後のPCMを保証できないため、入力キャプチャが使えない場合だけ `Visualizer(session 0)` をfallbackにします。入力PCMを `AudioTrack` へ戻さないため、解析開始で二重再生は起こしません。これは観測tapであり、音声を加工して差し替えるAudioPlaybackCapture backendではありません。Android 10以上では録音権限とMediaProjection同意が必要です。

選択中プリセットの周波数・ゲイン・MBC・歪み緩和・Fading値は、詳細設定のスライダーで調整できます。周波数6点は`−` / `＋`ボタンでも増減でき、低域・高域の傾斜途中にある中間境界とゲインも個別に追い込めます。変更は約80 msの補間で再生中のDynamicsProcessingへ反映され、DataStoreへ保存して再起動後も復元します。第一面の周波数カーブは20 Hz〜20 kHzを対数軸で描きます。これは調整値から計算した目安であり、端末のnative effect出力を直接読み取ったものではありません。Hiss / Crackle は第一面のスイッチと、プリセットと同じ `‹ ›`・セグメント帯で `-24〜+12 dB` を操作します。

## 代替案

Global AudioEffect が成立しない場合に、音声を加工して差し替える方式として `AudioPlaybackCapture` を調査します。上記のスペアナは観測専用です。差し替え方式には以下の制約があります。

- MediaProjection のユーザー許可が必要
- 再生側アプリの capture policy に依存
- 元音声の抑制が難しく、加工音との二重再生が発生し得る

したがって、現時点では第一選択ではありません。Mono差し替えPoCは元音声との二重化が実機で確認されたため、製品コードから削除しています。

## 開発環境

詳細は `docs/development.md`。最短は:

```bash
./gradlew test
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

package / applicationId は `dev.hondasports.razio`。

## ドキュメント

詳細は `docs/` 配下を参照してください。

- `docs/product-spec.md`
- `docs/architecture.md`
- `docs/audio-research.md`
- `docs/development.md`
- `docs/testing.md`
- `docs/roadmap.md`
- `docs/agent-skills.md`（`npx skills` で入れた skill の使い方）

AI エージェント向けルールは `AGENTS.md` に記載します。
