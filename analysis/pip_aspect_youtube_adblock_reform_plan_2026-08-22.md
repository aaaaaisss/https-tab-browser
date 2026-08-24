# 実映像PiP比率・YouTube多層広告遮断: 実装計画

## PiPの向き・比率

現在はfullscreen custom viewの外枠（端末画面の形）をPiPアスペクト比に使用している。これを、HTML `<video>` 要素が公開する `videoWidth` / `videoHeight` に変更する。

| 項目 | 実装 |
| --- | --- |
| 実映像サイズの取得 | `loadedmetadata`、`resize`、`playing`、DOM追加を監視する軽量scriptで最大の再生videoを選ぶ。 |
| 通知経路 | `@JavascriptInterface` は `videoWidth` / `videoHeight` の数値のみ受ける。WebView callbackからBrowserScreenを経由しMainActivityへ渡す。 |
| PiP比率 | 実映像サイズがAPI許容範囲内なら `Rational(width, height)` を使用する。横長は横長、縦長は縦長になる。 |
| 遷移矩形 | `sourceRectHint` は従来どおりfullscreen custom viewの可視画面座標を使う。これは移動アニメーション用であり、映像比率とは分離する。 |
| 失敗時 | メタデータを取得できないサイトでは現在のview外枠比率へ安全にフォールバックする。 |

## YouTube広告遮断

### 基盤フィルタ

1. 現行のAdGuard Android optimized 101と7を維持する。
2. AdGuard Base optimized（EasyList + AdGuard English）を標準追加する。汎用ネットワーク規則・cosmetic規則の不足を補う。
3. Brave Specificを標準追加する。BraveがYouTubeのnavigation/theater/playback speed/background playbackに使う補助scriptletを得る。

新しい標準リストは、同梱snapshotを用意し、手動更新・7日自動更新の既存経路へ入れる。Brave trusted scriptlet権限は、レビューした組込み標準リストだけに与える。

### YouTube専用補助

uBlock Originの現行YouTube広告対策を参考に、外部uBlock resource依存のscriptlet名は読み込まず、ねこぶらうざの組込みdocument-startスクリプトへ同等の最小処理を実装する。

1. `ytInitialPlayerResponse`、`playerResponse`の `adPlacements`、`playerAds`、`adSlots` を無効化する。
2. `fetch` と `XMLHttpRequest` をラップし、`youtubei/v1/player`、`get_watch`、playlist、Shorts reel sequenceのJSONレスポンスから同広告キーを再帰的に削除する。
3. YouTube判定時だけ実行し、動画バイト列、media request、URL遷移、認証Cookieには触れない。
4. 既存の攻めたモードでは、ネットワーク規則もmedia/subdocumentを含めて全評価する。通常モードは再生の安全性を優先する。

### 非採用

| 候補 | 理由 |
| --- | --- |
| AdGuard CoreLib | OSSの無制限組込み代替ではなく、個人用WebViewアプリの依存として適切ではない。 |
| uBlock filters全体をtrusted scriptletとして読込 | uBlock独自resourcesの全同梱・メンテナンスが必要で、未解決scriptletで遮断不全・ページ破損が起こる。YouTube対策は同等の最小コードへ限定する。 |
| `$replace`ネットワーク規則の直接適用 | WebViewには任意サブリソースを安全に再発行する公開APIがない。JSON応答をpage-sideで処理する。 |

## 検証

1. GitHub ActionsでRust test、ARM native build、Android Lint、unit test、Release assembleを実行する。
2. 実機では横長YouTube、縦長YouTube Shorts、端末縦持ち/横持ち、全画面ボタン/ホームジェスチャーのPiPを確認する。
3. YouTubeは通常モードと攻めたモードで、通常動画・Shorts・プレロール広告・途中広告を別々に確認する。
