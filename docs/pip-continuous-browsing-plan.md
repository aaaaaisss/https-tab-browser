# PiP再生中のブラウズ継続：段階実装方針

**状態:** ユーザー承認済み。**段階1・2を実装済み、段階3の再生要求連携は保留。**

## 目的

Androidのsystem PiPで動画を継続再生しながら、URL入力、タブ操作、ページ閲覧、ホーム、設定などの通常ブラウズを続けられるようにする。

## 制約

system PiP中のActivityは小窓になり、通常のアプリUIに入力フォーカスを持たない。したがって、現在の`MainActivity`へブラウズUIを残したままPiP中に操作する方法は採用しない。

特に、再生中のWebViewまたはfullscreen custom viewをActivity間で移動・再親子化してはならない。これまでの動画停止を再発させるためである。

## 採用候補：二Activity構成

| 役割 | Activity | 責務 |
|---|---|---|
| PiP再生host | 既存`MainActivity` | fullscreen custom viewとPiP動画surfaceを所有し、PiP中も再生を継続する。 |
| 通常ブラウズ | 新規`BrowserActivity` | URL入力、タブ、ホーム、設定、ダウンロードを表示・操作する。再生hostのWebViewは復元・移動しない。 |

PiPのカスタム操作からブラウズActivityを前面に出し、動画ActivityはPiPのまま残す。ブックマークと設定はRepository/永続状態を経由して共有する。タブのメタデータ、履歴、選択状態とWebViewのViewインスタンスは共有しない。

## 段階的導入

1. **最小実証（実装済み）:** PiP操作に「ブラウズを開く」を追加し、新しいdocument taskの`BrowserActivity`を前面化する。PiP動画のWebView/custom viewは`MainActivity`に残し、Activity間で再親子化しない。
2. **共有状態（実装済み）:** `BrowserActivity`は新規ホームから開始し、永続タブ・履歴・選択状態を保存しない。一方で設定とブックマークは専用のDataStore更新経路で共有する。
3. **再生要求（保留）:** ブラウズ側で選んだ新しい動画を、明示的なID/IntentでPiP再生hostへ渡す。既存PiPを不安定化させるため、実機確認後まで着手しない。
4. **回帰検証（ローカル完了・実機確認待ち）:** PiP開始、ブラウズ起動、戻る、PiP終了、通常タブ再生を静的回帰ガード、ユニットテスト、Lint、APK生成で確認した。動画の連続再生は端末で最終確認する。

## 非目標

- PiP小窓へアドレスバーやタブUIを置くこと。
- system PiPを廃止してアプリ内ミニプレーヤーだけに置き換えること。
- YouTube広告遮断やSABR対策を、この構造変更と同時に変更すること。

## 次の実機確認

PiP中に表示される「ブラウズを開く」操作を選び、動画が小窓で継続再生すること、開いた`BrowserActivity`でURL入力・タブ・設定・ブックマーク操作が行えることを確認する。通常のブラウザへ戻った際の既存PiP再生を変更する処理は含めない。
