# Fulguris由来コードに関するCPAL-1.0通知

本プロジェクトには、Fulgurisの暗色化判断経路から派生・適合したコードが含まれます。

| 項目 | 内容 |
|---|---|
| 対象ファイル | `app/src/main/java/com/example/httpsbrowser/web/FulgurisDarkModeController.kt` |
| 原コード | Fulguris `app/src/main/java/fulguris/view/WebPageTab.kt` の `applyDarkMode()` を中心とするWebView設定経路 |
| 原プロジェクト | [Fulguris](https://github.com/Slion/Fulguris) |
| 原著作権表示 | Copyright © 2020–2021 Stéphane Lenclud、Copyright 2014 A.C.R. Development |
| 対象ライセンス | [Common Public Attribution License 1.0（CPAL-1.0）](LICENSES/CPAL-1.0.txt) |
| 初期開発者 | Stéphane Lenclud |
| 変更日 | 2026-08-21 |
| ソース提供先 | [aaaaaisss/https-tab-browser](https://github.com/aaaaaisss/https-tab-browser) の本ファイルおよび本通知 |

## 変更内容

FulgurisのActivity、Hilt、RxJava、独自タブモデル、独自`WebViewEx`へ結合した大規模クラスから、WebView設定に必要な判断だけを `FulgurisDarkModeController` へ抽出して適合しました。AndroidX WebKitのfeature gate、`setAlgorithmicDarkeningAllowed`、Force Dark strategy、親Viewの`forceDarkAllowed`を保っています。

ねこぶらうざでは、画像・動画をWebView全体で反転させない方針のため、Fulgurisの古いColorMatrix反転フォールバックと`img`再反転スクリプトは意図的に採用していません。Dark Readerとページ内の反復背景CSS注入を取り除き、ネイティブWebViewの色テーマ経路を唯一の暗色化主経路にしました。

## 起動時帰属

CPAL-1.0 §14およびFulgurisの公開Termsに沿い、アプリ起動時に次の文言を可視表示します。

> Powered by Fulguris Browser

この通知はFulgurisの商標・帰属を示すものであり、Fulgurisが本プロジェクトを支持・保証することを意味しません。

## 対応ソースの利用可能期間

APKを配布する限り、上記の対象ソースおよびCPAL-1.0通知は本リポジトリで利用可能にします。第三者へAPKを渡す場合も、この通知、対象ソース、CPAL-1.0本文へのリンクを添付または案内してください。
