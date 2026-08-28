# Product Specification

## プロダクト名

RAZIO

## コンセプト

現代の Android 端末で再生される音声を、昔の AM ラジオのような音へ変換して楽しむ。

## 対象ユーザー

- レトロな音質が好きな人
- 音楽、動画、ゲームを古いラジオのような質感で楽しみたい人
- 個人用途で Android の音声体験を遊びたい人

## コア価値

RAZIO の価値は、高忠実度な AM 通信シミュレーターではなく、日常的なスマホ音声を「古いラジオで鳴っているように聞かせる」ことです。

## MVP

MVP では以下を目標にします。

- AM Effect ON / OFF
- 他アプリのメディア音声へ可能な範囲で効果を適用
- AM ラジオ風の帯域制限
- 中域強調
- Compression / Limiter
- 現在の AudioEffect 対応状態を表示
- 効果適用失敗をユーザーに明示

## 非目標

初期段階では以下を必須にしません。

- 放送規格として正確な AM 変調・復調シミュレーション
- root 化
- custom ROM
- system app 化
- DRM 保護音声の回避
- capture を禁止しているアプリの制約回避
- ノイズやフェージングの高度な物理シミュレーション

## UX 方針

UI は昔のポケットラジオを想起させる方向を候補としますが、音声 PoC 完了までは機能優先です。

最低限の画面要素:

- RAZIO ON / OFF
- 状態: Active / Unsupported / Error
- プリセット
- 現在の効果説明

将来候補:

- Normal AM
- Weak Signal
- Distant Radio
- Vintage Speaker
- Shortwave-like

## 成功条件

PoC 成功:

- root なしの実機で、少なくとも主要なメディア再生アプリ 1 つ以上に対して RAZIO の ON/OFF により明確な音質変化を確認できる
- 元アプリの変更を必要としない
- 再起動なしで ON/OFF を切り替えられる

MVP 成功:

- 対応端末で安定して切り替え可能
- 非対応時にクラッシュしない
- AudioEffect の状態を UI から確認可能
- build/test/install の手順が自動化可能

## 重要な制約

Android の global output mix に対する AudioEffect は deprecated API を含み、OS / ベンダー / Audio HAL に依存します。

このため RAZIO は、最初から「全 Android 端末で全アプリへ必ず適用できる」とは定義しません。対応範囲は実機検証から決定します。
