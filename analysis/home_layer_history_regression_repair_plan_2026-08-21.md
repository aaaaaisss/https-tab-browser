# ホーム画面レイヤー・履歴復活の回帰修正計画

**作成日:** 2026-08-21（日本時間）  
**対象:** `e798393` のActivity native WebView host版

## 原因

今回のnative host化では、Activity rootへ `ComposeView` を追加した後に通常ページ用 `normalWebContentHost` を追加し、さらにページ表示時に `bringToFront()` を呼んでいた。このためnative hostがCompose全体より前面へ移動し、ページ領域だけでなくComposeによる右端スクロールレール、下部操作バー、ホーム画面、各シートを視覚的・入力上覆う状態になった。ホームへ戻る際にhostの子Viewを外す処理自体は存在するが、Composeより前面へ出す設計は、表示状態の非同期更新と組み合わさるとホームUIを隠し、操作不能にし得る。

ホームへ戻る処理は、`BrowserViewModel.openHome()` がUI状態だけをホームへ戻していた。生存中WebViewのChromium履歴は残るため、同タブでホームから別ページを開くと、過去のサイトAへ戻れたり、古い進む履歴へ進めたりする。これは独自ホームをWebView内の履歴項目にしていない構造に対し、UI状態とWebView履歴を別々に扱ったことによる不整合である。

## 修正方針

| 項目 | 修正 |
|---|---|
| Activityのレイヤー順 | `normalWebContentHost` を最下層、`ComposeView` を中層、full-screen video containerを最上層へ置く。 |
| ページ描画の安定性 | 選択WebViewはActivityのnative hostに直接接続したままとし、Compose `AndroidView`へ戻さない。 |
| 下部バー・ホーム・シート | Composeを常にnative hostより前面に固定する。ページ領域はCompose側で透明なため、通常ページの表示・タップはhostへ届く。 |
| hostの前面化 | `normalWebContentHost.bringToFront()` を全廃する。full-screen custom viewだけは必要時に最上層へ追加する。 |
| ホーム復帰 | `returnSelectedTabToHome()` の直前にRegistryがWebViewを `about:blank` へ戻し、履歴・進む履歴を初期化する。 |
| 戻る判定 | WebView履歴の直前項目がHTTPSでない場合（reset用 `about:blank` 等）は戻る操作として扱わず、独自ホームへ戻す。これによりサイトA→サイトB→サイトAまでは戻れ、サイトAより前の古いページへは戻れない。 |

## 受入条件

1. 通常ページでアドレスバー、ナビゲーション列、タブバーが従来通り画面下部に常時表示され、検索中にも隠れない。
2. ホームへ戻ると、ブックマークと下部操作が表示され、タップできる。
3. ホームからサイトAを開き、ホームへ戻ってページBを開いた後の戻る操作は、サイトAではなくホームへ戻る。
4. 再びホームからサイトAを開いた時、進む操作で過去のページBへ移動できない。
5. native host化により改善したYouTube通常表示・Google動画タブ・動画映像の非反転は維持する。
