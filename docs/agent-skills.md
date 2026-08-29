# Agent Skills

Loop skill（`skills/`）は段階の進め方。`npx skills` で入れた domain skill（`.agents/skills/`）は Kotlin / Compose / Gradle / Android CLI のやり方。両方使う。置き換えない。

## 置き場所

| 種類 | 場所 | 役割 |
| --- | --- | --- |
| Loop | `skills/*/SKILL.md` | PREPARE / IMPLEMENT / VERIFY / COMMIT |
| Domain | `.agents/skills/*/SKILL.md` | 実装・検証の専門手順 |
| Lock | `skills-lock.json` | `npx skills` の導入記録 |
| この文書 | `docs/agent-skills.md` | どれを読むかの正本 |

常時ロードは `AGENTS.md` + `.loop/process.yaml` + 今の Loop skill。domain skill は matching したものだけ読む。全部は読まない。

## 選び方

1. 今の Loop skill を読む。
2. 下の表で今回の変更に当たる domain skill を 1〜2 個読む。SKILL.md を先に読む。reference は必要になってから。
3. Kotlin / Compose が複数同時に絡む時だけ `using-chrisbanes-skills` で絞る。1 本で足りるなら router は読まない。
4. この文書の RAZIO override が、upstream skill のデフォルトより優先する。

## カタログ

### ほぼ毎回

| 状況 | Skill | 読むタイミング |
| --- | --- | --- |
| `./gradlew` / `gradlew.bat` / test / lint / assemble | `gradle-run` | Gradle を実行する直前。Implementation と Verification の両方 |
| 実機の install / 起動 / screenshot / layout / Android 公式 docs | `android-cli` | Verification。PREPARE で Android API を確認する時 |

### Kotlin / Compose

複数が同時に絡む時の入口: `using-chrisbanes-skills`

| 状況 | Skill |
| --- | --- |
| Compose の state / effect / 画面の持ち主 | `compose-state-and-effects` |
| modifier / slot / コンポーネントの形 | `compose-component-design` |
| Compose UI test | `compose-ui-testing-patterns` |
| 関数の置き場所、domain type、platform 境界 | `kotlin-api-design` |
| `when` / null / exhaustiveness | `kotlin-control-flow` |
| coroutine の持ち主、Flow、cancellation | `kotlin-concurrency-and-flow` |

入っていない chrisbanes skill（`compose-performance` など）は表にあっても読まない。必要になったら `npx skills add` してこの表を更新する。

### 必要な時だけ

| 状況 | Skill |
| --- | --- |
| system bar / IME に UI が食われる | `edge-to-edge` |
| テスト基盤そのものを足す・変える | `testing-setup` |
| 性能調査（jank / メモリ / trace）。通常の Verification では使わない | `android-profiler` |

## Stage との対応

| Loop stage | 追加で読むもの |
| --- | --- |
| PREPARE | Android API が不明なら `android-cli` の docs。テスト基盤を変える計画なら `testing-setup` |
| IMPLEMENT | Gradle を回すなら `gradle-run`。Kotlin/Compose の該当 skill |
| VERIFY | `gradle-run` + `android-cli`。Compose UI test を書く/直すなら `compose-ui-testing-patterns` |
| COMMIT | domain skill は追加しない |

## RAZIO override

upstream skill をそのまま全部実行しない。

### gradle-run

- Gradle は wrapper 経由。直の `./gradlew` やシステム Gradle は使わない。
- Windows で `python3` が無い時は `py -3`。`python3` が無いことだけを理由に Gradle 直実行へ落とさない。
- Loop 内の `test` / `assembleDebug` は incidental validation。今の Agent が `create` → `run` → `finish` する。
- Solver 用の別 Agent は、Gradle 診断そのものがタスクの時だけ。
- `create` / `run` / `finish` はそれぞれ単独の shell コマンド。`&&` でつながない。
- フルログを chat に貼らない。summary JSON だけ読む。

例（Windows）:

```powershell
py -3 .agents/skills/gradle-run/scripts/gradle_run.py create
py -3 .agents/skills/gradle-run/scripts/gradle_run.py run --workflow <id> --scope targeted --question "Do unit tests pass?" -- .\gradlew.bat test
py -3 .agents/skills/gradle-run/scripts/gradle_run.py finish --workflow <id>
```

### android-cli

- 実機の第一手段は `adb`。`android` CLI は docs / screenshot / layout に使える時だけ足す。
- SDK 追加は `sdkmanager.bat`。このマシンでは Windows の `android` CLI が成功後に `0xC0000409` で落ちることがある。コマンド自体が成功しているなら再実行しない。
- USB が見えても `adb devices` が空なら、PTP/充電のみの可能性がある。USBデバッグとファイル転送を確認する。emulator を足して逃れない。

### testing-setup

- 既存スタックは JUnit。Hilt / Robolectric / Dropshots / Jacoco を skill の初期セットだからと足さない。
- UI test 基盤を本当に足す時だけこの skill を読み、Fixed stack と照合する。
- UI test 成功を system-wide audio の成立証明にしない。

### android-profiler

- ユーザーが性能調査を頼んだ時だけ。
- 通常の AudioEffect Verification は logcat / dumpsys。profiler で代替しない。

### edge-to-edge

- targetSdk を上げる理由には使わない。insets が実際に壊れている時だけ。

## 運用

```bash
npx skills update
npx skills add <owner/repo@skill>
```

足したら同じ変更で:

1. `skills-lock.json` が更新されていることを確認する
2. この文書のカタログを更新する
3. 今の RAZIO に不要な skill（Navigation 3、CameraX、Wear、Play Billing、Firebase など）は入れない

Loop の段階 skill を `.agents/skills/` に移さない。
