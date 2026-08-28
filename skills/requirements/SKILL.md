# Requirements

目的は、実装前の長い議論ではなく「何を観測できれば完了か」を最短で固定することです。

## やること

1. Goal / scope を1〜3行で固定する。
2. Acceptance Criteria を観測可能な結果として列挙する。
3. cheapに確認できる不明点はコード・docs・Android公式仕様から確認する。
4. Android端末依存の仮定は、質問で確定したことにせず実機Verificationへ回す。
5. Riskを low / medium / high で付ける。
6. Verification planを最短の順序で作る。

## 止める条件

- material choiceが複数残り、実装で勝手に選ぶと仕様が変わる
- user instruction と既存仕様が衝突している

それ以外の細部は実装・検証で解決し、PREPAREを肥大化させません。
