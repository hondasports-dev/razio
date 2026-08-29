# Implementation

RAZIOでは短い変更単位で buildable な状態を維持します。

## Rules

- Acceptance Criteriaに直接必要な最小差分から実装する。
- 音声経路の成立性をUI完成より優先する。
- 1つの大変更より、compile可能な小さい変更を積む。
- Android API / deprecated APIの仮定を隠さず、失敗状態を観測できるようにする。
- AudioEffect生成・enable・releaseはlifecycleと例外経路を明示する。
- 新依存は標準APIで足りない時だけ追加する。

## Fast feedback

大きな変更を溜めず、必要に応じて途中で以下を使います。

```bash
./gradlew test
./gradlew assembleDebug
```

compile/build failureを抱えたまま別機能へ進みません。unit test と必須の実機確認が通ったらすぐ commit します。
