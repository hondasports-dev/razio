# Implementation

RAZIOでは短い変更単位で buildable な状態を維持します。

## Domain skills

この Loop skill と併用する。選び方の正本は `docs/agent-skills.md`。

- Gradle を回す直前に `.agents/skills/gradle-run/SKILL.md` を読む。直の `./gradlew` は使わない。
- Kotlin / Compose が複数同時に絡む時は `.agents/skills/using-chrisbanes-skills/SKILL.md` で1本に絞る。
- 該当する Compose / Kotlin skill の SKILL.md を読んでから書く。

## Rules

- Acceptance Criteriaに直接必要な最小差分から実装する。
- 音声経路の成立性をUI完成より優先する。
- 1つの大変更より、compile可能な小さい変更を積む。
- Android API / deprecated APIの仮定を隠さず、失敗状態を観測できるようにする。
- AudioEffect生成・enable・releaseはlifecycleと例外経路を明示する。
- 新依存は標準APIで足りない時だけ追加する。

## Fast feedback

大きな変更を溜めず、必要に応じて途中で `gradle-run` から `test` / `assembleDebug` を回します。手順は `docs/agent-skills.md`。

compile/build failureを抱えたまま別機能へ進みません。unit test と必須の実機確認が通ったらすぐ commit します。
