# ねこぶらうざ — 4本柱再構築リリース記録

**対象コミット:** `1bc1abc5849aa91195c5cb0c2e820befed847092`

**再構築ブランチ:** `rebuild/121e47b-four-pillars`

**基準タグ:** `baseline-before-121e47b-rebuild-eabbabc`
**作成日:** 2026-08-21

## 目的

本リリースは、WebViewベースのねこぶらうざについて、暗色化、動画表示、翻訳、広告遮断を互いに独立したコミットで再構築したものである。特にYouTube通常動画、Shorts、Google検索の動画タブ、全画面、PiPの描画経路に、ページ暗色化・汎用cosmetic CSS・翻訳用DOM改変が重ならないことを設計原則とする。

| 柱 | 採用した構成 | 明示的に採用しなかった構成 |
|---|---|---|
| 暗色化 | `121e47b`時点のWebView標準API、動画文書除外、一般ページ用深いCSS | Fulguris native dark-mode controller、Dark Reader、暗色化切替時のreload |
| 動画表示 | Fulgurisのロード状態再arm、重複`onPageFinished`抑止、custom viewの単一所有・PiP保持 | Fulguris全UI、Hilt、RxJava、動画用外部プレーヤー、player response JSON改変 |
| 翻訳 | Fulguris型のGoogle Translate URL遷移 | ML Kitの言語識別・端末内翻訳モデル・本文DOM置換 |
| 広告遮断 | Brave adblock-rust 0.13.3、指定2フィルタ、動画必須要求の限定保護 | Brave Browser本体のChromium Shields移植、任意リストのscriptlet権限、YouTube JSON改変 |

## 実装履歴

| コミット | 内容 | GitHub Actions検証 |
|---|---|---|
| `dffdd4d` | `121e47b`型暗色化へ復帰。動画文書を除外し、一般文書には従来の深いCSSを適用 | 成功: [run 32470871245](https://github.com/aaaaaisss/https-tab-browser/actions/runs/32470871245) |
| `2c59dd5` | Fulguris由来のページライフサイクル・fullscreen custom viewの安全策を選択移行。CPAL通知を更新 | 成功: [run 32471901329](https://github.com/aaaaaisss/https-tab-browser/actions/runs/32471901329) |
| `16565fe` | Google Translate URL遷移へ翻訳を変更し、ML Kit依存と`PageTranslator`を削除 | 成功: [run 32472692064](https://github.com/aaaaaisss/https-tab-browser/actions/runs/32472692064) |
| `1bc1abc` | Brave adblock-rustを0.13.3へ更新し、動画再生必須要求のみを限定保護 | 成功: [run 32473829917](https://github.com/aaaaaisss/https-tab-browser/actions/runs/32473829917) |

## 重要な設計判断

### 暗色化

暗色化は、`121e47b`の構成を意図的に復帰した。一般文書ではAlgorithmic Darkening、Force Dark、`DARK_STRATEGY_USER_AGENT_DARKENING_ONLY`、およびページ用深いCSSを使用する。一方、YouTubeとGoogle動画タブはこの変換の対象外とする。これにより、映像を含む`video`、`canvas`、`iframe`の合成面へ一般ページ向けのCSS反転が干渉することを避ける。

### 動画とPiP

Fulgurisの`WebPageClient`が採る、main-frame要求・`onPageStarted`・戻る・進む・再読込時の完了状態再armを適合した。`WebView.progress == 100`の最初の`onPageFinished`だけで後処理を実行するため、YouTubeの重複完了通知で広告CSS、Cookie書込み、履歴UI更新を繰り返さない。[1]

全画面はWebChromeClientのcustom viewを単一所有し、重複開始を拒否する。PiPへの遷移中に`onHideCustomView`が先行してもviewを切り離さず、通常の全画面終了時だけkeep-screen-on、親View、system bars、callbackを復帰する。[2]

### 翻訳

翻訳ボタンは現在タブを次の形式のGoogle Translate URLへ遷移させる。

> `https://translate.google.com/translate?sl=auto&tl=<端末ロケール>&u=<現在URL>`

この方式はFulgurisの翻訳経路に合わせたもので、ブラウザの戻る操作で原文へ戻れる。[2] ML Kitの翻訳・言語識別依存とJNI資産を削除したため、部分翻訳やページ内DOM置換を行わない。Google側がCAPTCHA、ログイン、または対象ページの制限を表示した場合は、WebViewアプリ側で迂回しない。

### 広告遮断

広告遮断はBraveの`adblock-rust`を0.13.3へ更新した。adblock-rustはnetwork blocking、cosmetic filtering、uBlock Origin構文拡張、resource replacement等を提供するBraveのネイティブ広告遮断ライブラリである。[3] [4]

WebViewはBrave BrowserのChromium network serviceやShields APIへ接続できないため、Brave Browser本体のShieldsを丸ごと移植することはできない。その代わり、JNI bridgeはnetwork判定をネイティブengineへ委譲する。YouTube・Google動画タブでは`media`、`subdocument`、`script`、`xmlhttprequest`のうち映像復号に必要な宛先だけを誤遮断から保護し、画像・stylesheet・fontなどの非必須要求は指定2フィルタで通常どおり評価する。player response JSONの改変は採用しない。

## 受入基準と実機確認

GitHub ActionsでRust native library、Kotlinコンパイル、ユニットテスト、Lint、固定署名Release APK生成が各段階で成功している。ただし、WebView実装差・端末GPU・YouTubeの配信状態・Google Translateのアクセス制限は端末上でのみ確認できる。

| 優先度 | 実機で確認する内容 | 期待値 |
|---|---|---|
| 必須 | Google検索、一般サイト、画像主体ページの暗色化 | 一般文書は暗色化され、読み込み中の黒背景が維持される |
| 必須 | YouTube通常動画、Shorts、Google動画タブからの再生 | 映像・音声がともに表示・再生され、白黒レイヤーが重ならない |
| 必須 | 全画面動画からホーム操作 | 全画面中のみPiPへ遷移し、通常ページではPiPに入らない |
| 必須 | 戻る・進む、OS戻る | WebView履歴を再読込せずに優先し、履歴が尽きたらアプリを終了する |
| 必須 | 英語等のページで翻訳ボタン | Google Translateへ遷移し、戻るで原文へ戻る |
| 必須 | `101_optimized`と`7_optimized` | 広告遮断が有効で、再生必須動画要求を止めない |
| 推奨 | 既存APKからの上書き更新 | アプリデータとログイン状態が維持される |

## ライセンスと帰属

Fulguris由来の動画ライフサイクル、full-screen custom view管理、Google Translate遷移にはCPAL-1.0の通知・対応ソース案内を維持する。起動時には `Powered by Fulguris Browser` を表示する。詳細は [`FULGURIS_CPAL_NOTICE.md`](../FULGURIS_CPAL_NOTICE.md) と [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) を参照する。

## 参考資料

[1] [Fulguris `WebPageClient`](https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/view/WebPageClient.kt)

[2] [Fulguris `WebBrowserActivity`](https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/activity/WebBrowserActivity.kt)

[3] [Brave adblock-rust repository](https://github.com/brave/adblock-rust)

[4] [adblock 0.13.3 on crates.io](https://crates.io/crates/adblock)

[5] [Brave: adblock-rust memory architecture update](https://brave.com/privacy-updates/36-adblock-memory-reduction/)
