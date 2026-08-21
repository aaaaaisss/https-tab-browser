# Fulguris由来ダークモード移行設計

作成日: 2026-08-21（日本時間）

## 方針

Fulguris `WebPageTab.applyDarkMode()`のネイティブWebView経路を、CPAL-1.0対象の専用ファイルとして直接移植・適合する。Dark Readerとページ内の背景CSS反復注入を主経路から完全に取り除く。動画・画像への全WebView反転を避けるため、Fulgurisの`ColorMatrix`旧端末フォールバックは採用しない。

これはFulgurisの大きな`WebPageTab`全体を持ち込むものではない。同クラスはActivity、Hilt、RxJava、独自タブモデル、独自View、独自設定と強く結合しており、移行すると現在のCompose UI、Brave広告遮断、PiP、タブ管理を壊す。暗色化に関わるWebView設定の判断部だけを、著作権・CPAL通知・変更記録を保持して専用controllerへ隔離する。

## 移行後の単一路径

```text
BrowserSettings.forceDarkPages
  └─ FulgurisDarkModeController.apply(WebView)
      ├─ WebView parent: forceDarkAllowed = true
      ├─ Algorithmic Darkening: enabled/disabled
      ├─ Force Dark: API/feature対応端末の互換経路
      └─ Strategy: PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING

Activity theme: isLightTheme = false
  └─ WebViewの prefers-color-scheme: dark
      └─ サイトが公式dark themeを持つ場合はサイト自身のテーマを優先
```

## 明確に廃止する現行要素

| 廃止対象 | 理由 |
|---|---|
| 同梱Dark Reader v4.9.128のDocument Start注入 | ネイティブ暗色化と競合し、handler登録成功だけで標準経路を止める構造を排除する。 |
| `DARK_BASE_STYLE_SCRIPT` とページ開始/commit/完了時の注入・除去 | WebView背景とアプリテーマで初期背景を統一し、SPA・動画iframeと競合するページ内style操作を撤去する。 |
| YouTube、Google動画、ログイン、決済のDark Reader除外一覧 | ページ内変換器を使わなくなるため不要。動画ページにもWebView本来の`prefers-color-scheme`を提供する。 |
| Dark Reader asset整合性検査・第三者通知 | 実行資産として使わないため、APKから削除し通知も削除する。 |

## 維持する現行要素

| 要素 | 維持理由 |
|---|---|
| WebView背景色 #000000 | ページ読み込み前の表示を黒に保つ。 |
| `overScrollMode=NEVER`、scrollbar無効化、通常レイヤー | 動画表示・独自右端レール・Google動画タブの修正を保持する。 |
| Brave network filtering とYouTube限定広告CSS | 暗色化から独立しているため変更しない。 |
| JavaScript無効時にも使えるネイティブ暗色化 | Dark Reader依存をなくし、JS設定と暗色化を分離する。 |
| Android 8〜最新WebViewのfeature gate | `WebViewFeature`確認により端末ごとに使用可能な公式APIだけを呼ぶ。 |

## CPAL-1.0対応

1. `FulgurisDarkModeController.kt`にFulguris由来であること、原著作権、CPAL-1.0、元URL、変更日を明示する。
2. `FULGURIS_CPAL_NOTICE.md`へ元コード、変更内容、ソース提供先、CPAL-1.0本文への参照を記録する。
3. `THIRD_PARTY_NOTICES.md`を「設計参照」から「直接移行したCPALコード」へ更新する。
4. 起動時に`Powered by Fulguris Browser`を既存notice表示で提示する。
5. APK配布時にはGitHubの対象ソース・通知・CPAL本文へのリンクを明示する。

## 実機確認の必須項目

1. 一般的な明るいサイトで、画像を反転せず背景・本文が暗色化されること。
2. `prefers-color-scheme`対応サイトがサイト本来のdark UIを選ぶこと。
3. YouTube通常動画、Shorts、Google動画タブがダークテーマ下でも映像・音声・全画面・PiPを維持すること。
4. ダークモードON/OFF切替直後に、タブ履歴や動画再生を不必要に再読込しないこと。
5. Android 8〜12の端末で、公式Force Dark互換経路が機能すること。
