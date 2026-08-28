# Code Review

Reviewは標準Gateではありません。high risk、横断的なaudio architecture変更、実装中にmaterial riskが増えた場合だけ1回実施します。

## Input

- Goal / Acceptance Criteria
- behavior-changing diff
- device / test Evidence
- open findings
- Android固有の仮定

## Focus

styleより先に以下を確認します。

- ACの実装漏れ
- Android API / Audio HALについて証明されていない仮定
- 必要な実機Evidenceの欠落
- lifecycle / failure / release経路
- scope外変更

reviewer同士の討論は行いません。findingはroot Agentが統合します。
