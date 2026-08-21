# Fulguris とねこぶらうざのダークモード比較

作成日: 2026-08-21（日本時間）

## 調査対象

- Fulguris `main` の `app/src/main/java/fulguris/view/WebPageTab.kt` および `WebPageClient.kt`、`app/src/main/js/InvertPage.js`
- ねこぶらうざ `BrowserWebView.kt`（コミット `5f43128`）
- Android Developers: *Darken web content in WebView*、Android 13 WebView color-theme behavior change
- Dark Reader公開資料

## 事実として確認できた相違

| 観点 | Fulguris | ねこぶらうざ（現行） | 意味 |
|---|---|---|---|
| 主経路 | AndroidX WebKitの`setAlgorithmicDarkeningAllowed`、`setForceDark`、`setForceDarkStrategy`をWebView設定として使用する。 | Document Startで黒背景CSSを注入し、同時にDark Reader v4.9.128をDocument Start実行する。Dark Readerがある場合はWebView標準暗色化を停止する。 | FulgurisはChromium/WebView内のネイティブ色変換を中心にするのに対し、現行はページ内スクリプトによる再スタイルを主経路にしている。 |
| `prefers-color-scheme` | アプリテーマのdark状態でWebViewにweb authorのdark themeを要求する。強制暗色化時は`PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING`を選ぶ。 | アプリテーマもdarkだが、Dark ReaderがページCSSを解析・置換するため、ページ作者のテーマよりDark Readerの動的テーマが優先される。 | Fulgurisはサイト標準のdarkテーマと親和的。現行はサイトのSPA、Shadow DOM、動画プレーヤーと競合し得る。 |
| Android 13+ | `setAlgorithmicDarkeningAllowed(settings, darkMode)`を有効化し、Fulguris自身のdark app themeと組み合わせる。`setForceDark`呼出しも残すが、target 33+でのForce Dark無効化はAndroid側の仕様。 | Algorithmic Darkeningを使える場合でも、Dark Reader handlerが登録済みなら`useWebViewDarkening=false`として無効化する。 | 新しい端末で安定した標準経路を自ら外し、Dark Readerだけに依存する場面がある。 |
| 白フラッシュ対策 | WebViewの背景をアプリテーマの背景色へ設定する。ページCSSの注入を前提にしない。 | Document Startと`onPageStarted`/`onPageCommitVisible`/`onPageFinished`で黒背景styleを繰り返し注入・除去する。 | 現行は白フラッシュを抑える意図だが、SPA遷移・iframe・動画の初期合成と複数の非同期タイミングで競合する余地がある。 |
| YouTube・Google動画 | ダークモード側で特別なURL除外は見つからず、WebViewの色テーマ設定に委ねる。 | YouTube、YouTube iframe、Google動画タブ、Googleログイン・決済をDark Readerから除外する。さらに動画URLで黒背景styleを除去する。 | 現行は動画安全性のために暗色化を外すので、YouTubeが明るいままになることは設計上想定された結果。 |
| 画像・動画 | 現行WebViewではネイティブ経路を使う。Force Dark未対応端末だけ、WebView全体をColorMatrixで反転し、ページ完了後に`img`だけをCSS反転して補正する旧フォールバックを持つ。`video`補正は確認できない。 | CSS `filter: invert()`は使わない方針。ただしDark Readerの内部動的CSSにより画像・背景画像の補正処理が行われる。 | Fulgurisも全世代で「画像・動画を必ず反転しない」仕組みではない。旧端末のColorMatrix経路では動画が反転する可能性があり、現行Dark Readerもサイト固有の誤判定を完全には避けられない。 |
| 実行の重なり | `initializePreferences`から暗色化設定を一箇所で適用する。 | WebView生成、設定変更、Document Start、ページ開始、commit visible、finishedの複数段階で暗色化状態を操作する。 | 現行は状態の所有者が分散しており、同じ文書に異なる方針が時系列で掛かり得る。 |

## Fulgurisの実装に関する重要な注記

Fulgurisは「Dark ReaderのようなページCSS変換器」を使っていない。`WebPageTab.applyDarkMode()`でWebView設定を変更する構造であり、現在のAndroid WebViewではアプリの`isLightTheme`に従って`prefers-color-scheme`が自動評価される。Fulgurisの`setForceDark()`とstrategy APIは古いWebViewとの互換経路も保持しているが、Android 13以降ではForce Dark自体はno-opであり、実際の中心はアプリテーマと`setAlgorithmicDarkeningAllowed()`である。

> Android公式は、target SDK 33以上では`setForceDark()`がno-opになり、WebViewがアプリテーマの`isLightTheme`に従って`prefers-color-scheme`を設定すると説明している。

## 現行実装の根本的な問題仮説

1. Dark Readerを「全般的な主経路」にしているため、WebView自身の色テーマ契約とページ解析型CSS変換が二重の状態機械になっている。
2. 動画保護のための広い除外条件により、最も要望が強いYouTube・Google動画タブでは強制暗色化を意図的に提供しない構造になっている。
3. 黒背景base styleをページライフサイクルの複数地点で追加・除去するため、遷移中にスタイルの残留・消去順序が描画面へ影響し得る。
4. `usesDarkReader`がDocument Start APIの成功だけで決まり、Dark Readerが実際に対象サイトへ適用されたかとは独立してWebViewネイティブ暗色化が無効になる。

## 今後の再設計方向（まだ実装しない）

- **標準経路をネイティブWebView暗色化へ一本化**する。アプリテーマをdarkに保ち、Android 13+では`setAlgorithmicDarkeningAllowed(true)`を基本とする。ページ作者のdark themeは`prefers-color-scheme`で最優先する。
- **Dark Readerは主経路から外し、必要時だけの明示的なサイト別フォールバック**へ下げる。動画・ログイン・決済・Google検索結果には注入しない。
- 背景の黒固定はWebView Viewの背景とアプリthemeで保証し、ページ内base styleの反復注入を廃止または最小化する。
- 実機で「ネイティブWebViewのみ」「サイト別Dark Reader」「暗色化OFF」を識別可能な診断へ分け、結果を混同しない。

## 参照

1. Fulguris WebPageTab: https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/view/WebPageTab.kt
2. Fulguris WebPageClient: https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/view/WebPageClient.kt
3. Fulguris InvertPage script: https://github.com/Slion/Fulguris/blob/main/app/src/main/js/InvertPage.js
4. Android Developers, Darken web content in WebView: https://developer.android.com/develop/ui/views/layout/webapps/dark-theme
5. Android 13 behavior changes, WebView color theme: https://developer.android.com/about/versions/13/behavior-changes-13#webview-color-theme
6. Dark Reader Dynamic Theme: https://darkreader.org/blog/dynamic-theme/

## Dark Reader APIを組み込む場合の追加確認

Dark Reader自身は、Webページを解析してDynamic Themeを生成するブラウザ拡張として開発されている。一方で公開APIはWebサイトに組み込める`DarkReader.enable()`を提供しており、ページ内実行そのものが禁止されているわけではない。API実装は`window`にWebExtension由来の`chrome` objectをstubとして追加し、動的テーマ生成器を起動する。[6]

ただし、公開資料がいう「画像の色を触らない」「高速」という性質は、Dynamic Themeの目標であって全サイト・全WebView・全動画の完全保証ではない。Dark ReaderのDynamic ThemeはDOM、CSS、背景画像を解析し、ページへstyleを追加する。したがって、標準WebViewの`prefers-color-scheme`だけで暗色テーマを選ぶFulgurisの主経路とは、処理対象も失敗モードも異なる。[6] [7]

現行はDark ReaderをDocument Startで実行できたという「handler登録の成功」を、WebViewネイティブ暗色化を止める条件にしている。しかしhandler登録はDark ReaderがそのサイトのDOM/CSP/SPA構造で最終的にテーマを生成・保持できたことを示さない。この非対称性は、ページが明るいままになる最も重要な構造的リスクである。

[6] https://github.com/darkreader/darkreader#using-dark-reader-for-a-website
[7] https://darkreader.org/blog/dynamic-theme/
