# RAZIO Agent Loop

RAZIO のループは、Android 実機開発向けの短い単一路線です。PR は標準に入れません。

## 通常経路

```text
PREPARE → IMPLEMENT → UNIT TEST → VERIFY ON DEVICE → COMMIT → DONE
```

同じ反復の中身:

```text
実装
→ ./gradlew test
→ ./gradlew assembleDebug
→ adb devices
→ adb install -r ...
→ 実機で変更箇所を操作
→ logcat
→ 必要なら dumpsys
→ 通ったら main へ commit / push
```

Review / Incident / Process Learning は必要時だけ起動します。

## 最優先事項

RAZIO は端末・Android バージョン・Audio HAL・出力先による差が大きいため、unit test だけで完了判定しません。

音声経路・AudioEffect・permission・lifecycle・user-visible behavior を変更した場合は、可能な限り同じ反復内で実機確認まで進め、通ったらすぐ commit します。

## 高速化の考え方

- Gate を増やさず Acceptance Criteria と Evidence で品質を担保する
- PR / merge-ready / aftercare を標準に置かない
- shared diff の writer は原則 1 体
- reviewer は標準では起動しない。高 Risk / 横断変更だけ 1 回
- 変更範囲に近い cheap check から開始する
- stage ごとに Issue や chat 全文を再要約しない
- logcat / build error がある時は原因を読んでから retry する
- 実機が使えるのに「実行できなかった」と記録して先へ進まない

## 実機 Evidence が必要な代表例

- global AudioEffect / session 0
- Equalizer / DynamicsProcessing
- audio routing / output device differences
- permission / foreground service / lifecycle
- YouTube・音楽アプリなど他アプリ再生への影響
- UI の ON/OFF や状態表示

エミュレーターは UI / basic behavior の補助には使えますが、system-wide audio の成立判定には使いません。

## BLOCKED の扱い

必須の実機確認ができない場合、commit して完了扱いしません。

1. `adb devices`、USB debugging、権限、APK install、端末状態を確認
2. 復旧できるものはその場で復旧
3. コード起因か環境起因かを切り分ける
4. 環境自体が提供されていない場合だけ `BLOCKED` とする

`BLOCKED` は PASS や NOT_REQUIRED の代わりではありません。

## Context

常時持つのは原則以下だけです。

1. `AGENTS.md`
2. `.loop/process.yaml`
3. 現在 stage の `skills/*/SKILL.md`
4. `.loop/templates/task-state.yaml` を元にした compact task state

追加資料は必要になった時だけ読みます。
