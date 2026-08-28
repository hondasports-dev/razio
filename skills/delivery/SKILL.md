# Delivery

Deliveryの標準targetは `merge_ready` です。PR作成だけで完了扱いしません。

## Before publish

- Acceptance Criteriaが確認済み
- 必須Gradle checkがPASS
- 必須実機VerificationがPASS
- blocking findingなし
- Riskが要求するReviewがPASS

## Publish

- task branchからPRを作る
- PR本文には変更内容、実行したtests、実機Evidence、既知制約を書く
- 実行していない必須checkをPASSのように書かない

PR作成後は `pr-aftercare` へ進みます。
