# 通常WebView表示経路の再構築計画

**作成日:** 2026-08-21（日本時間）  
**対象:** ねこぶらうざ `rebuild/121e47b-four-pillars`  
**目的:** 動画サイトの暗色化設定の選択性を維持したまま、通常動画・Shorts・全画面動画の映像面を常に非反転とし、YouTube通常表示およびGoogle動画タブの描画問題を、Compose `AndroidView` から独立したネイティブWebView表示経路へ移して検証する。

## 1. ユーザー要件の不変条件

動画サイトを暗色化するかどうかはユーザーの設定で選べなければならない。一方で、設定のON/OFFにかかわらず、通常動画、YouTube Shorts、iframe内動画、全画面custom viewの**映像画素自体は反転してはならない**。このため、動画サイトの本文を暗くする機能と、映像surfaceを暗くする機能は完全に分離する。

| 項目 | 保持する方針 |
|---|---|
| 動画サイト暗色化の選択 | `forceDarkVideoPages` を維持し、既定OFFのままにする。 |
| WebView標準暗色化 | YouTube、Shorts、Google動画タブでは常にOFFにする。 |
| 動画サイトの本文暗色化 | ユーザーが上書きをONにした場合だけ適用する。 |
| 映像反転防止 | CSSを使う場合も動画surfaceを明示的に通常色へ戻す。ただし動画サイト上書きOFFではページCSSを注入しない。 |
| Brave広告遮断・翻訳・タブ・ホーム・下部UI | 維持する。 |

## 2. 現行実装の監査結果

現行の `BrowserScreen.kt` は、通常ページの `WebView` をComposeの `AndroidView` として、`Column` のページ領域に所有させている。Activity root のネイティブ `FrameLayout` はfull-screen custom view専用であり、通常WebViewは依然としてCompose表示ツリー内でサイズ計測・親子関係・再構成の影響を受ける。

一方、Fulgurisは各タブがネイティブ `WebView` を自ら所有し、Activity/View系の親へ直接接続する。renderer終了時も親から外してWebViewだけを破棄し、タブ状態から再生成する。この「通常表示も含めたネイティブ親View管理」が、今回検証すべき差分である。

現行版では、動画ページに対するWebView標準暗色化は既に停止している。しかし動画サイト上書きON時の深いCSSが `video`、`iframe`、`canvas` 等を一律に二重反転する方式であり、YouTubeの複数surface・Shorts・全画面遷移を確実に区別できない。再構築では、動画文書に同CSSを注入するかどうかを設定値に限定し、注入時も動画要素だけでなく、WebKit/YouTubeのvideo container、Shorts player、picture-in-picture用surfaceを通常色に固定する。

Google動画タブでは、現在もページ後半でBrave cosmetic filterを適用している。ネットワークの再生必須要求はバイパス済みであるが、DOM/cosmetic適用がプレーヤーコンテナと干渉していないことを保証できない。動画タブでは、再生領域が確立するまでcosmetic注入を適用しない構造へ整理する。

## 3. YouTube共有URLが基本URLになる理由

現在の共有ボタンは `selectedTab.url` をそのまま `Intent.EXTRA_TEXT` に入れている。タブの `url` は `onPageFinished` の時だけ更新される。しかしYouTubeは単一ページアプリケーションとして動画選択後に `history.pushState()` 等でURLと画面を更新することがあり、これは必ずしも通常のmain-frame load、`onPageStarted`、`onPageFinished`を発生させない。その結果、タブには初期の `https://m.youtube.com/` 等が残り、共有も古いURLになる。

修正では、共有の直前に、選択タブの生存中WebViewから `WebView.url` を読み、HTTPS URLなら最優先で共有する。加えて `WebViewClient.doUpdateVisitedHistory` でタブ状態を更新するため、アドレスバー・履歴・再作成時のURLもYouTubeの動画URLへ追従させる。Android公式ドキュメントでは `WebView.getUrl()` は現在URLを返し、`doUpdateVisitedHistory()` はホストアプリが閲覧履歴を更新する通知である。[1] [2]

## 4. 再構築する表示経路

Activity rootを次の三層とする。

| レイヤー | 所有者 | 役割 |
|---|---|---|
| 下層 | `MainActivity.webContentHost: FrameLayout` | 選択タブの通常WebViewを直接保持する。Composeのrecompositionで親子関係を変更しない。 |
| 中層 | `ComposeView` | ホーム画面、アドレスバー、下部操作、設定・候補・ダイアログ、右端スクロールレールを描く。非ホーム時のページ領域は透明で入力を奪わない。 |
| 上層 | `MainActivity.fullscreenContainer: FrameLayout` | Chromiumが渡すfull-screen custom viewとPiPボタンだけを保持する。 |

タブ選択時にはRegistryが対象WebViewを `webContentHost` へ接続し、前タブWebViewは親から外して保持する。タブを閉じた時、rendererが終了した時、Activityが破棄される時だけ確実に破棄する。ホーム選択時は`webContentHost`から通常WebViewを外し、ComposeのホームUIを表示する。これにより、タブ切替でページを再読み込みしない要件も維持する。

表示領域はCompose側の測定に依存させず、Activityが操作バー高さの変化をネイティブhostへ伝える。初期実装では既存の下部操作バーの高さをComposeからコールバックで受け、`webContentHost` のbottom marginを更新する。上下のsystem insetもActivityで一元的に計算し、WebViewはその確定矩形だけを受け取る。

## 5. 実装順序と検証境界

1. `MainActivity` に通常WebView用の `FrameLayout` を追加し、full-screen containerとは別に管理する。
2. `BrowserWebViewRegistry` に attach/detach/currentUrl と、SPA履歴更新用コールバックを追加する。
3. `BrowserScreen` から通常ページ用 `AndroidView` を除き、透明オーバーレイとネイティブhostの表示領域同期へ置換する。
4. 動画文書の暗色化を設定値に限定し、通常動画・Shorts・iframe・full-screen映像を常に非反転とする。
5. Google動画タブでは、再生中にcosmetic DOM/CSS注入が動画コンテナに干渉しないよう再生文書用の隔離経路を採る。
6. ローカルのcompile/lint/unit test後、GitHub Actionsで固定署名Release APKを生成する。

この変更は表示の所有経路を切り替えるため、実装・コミット・クラウドビルドを分け、問題の切り分け可能性を確保する。

## 参考文献

[1] Android Developers, [WebView API reference](https://developer.android.com/reference/android/webkit/WebView)  
[2] Android Developers, [WebViewClient API reference](https://developer.android.com/reference/android/webkit/WebViewClient)  
[3] Fulguris, [`WebPageTab.kt`](https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/view/WebPageTab.kt)
