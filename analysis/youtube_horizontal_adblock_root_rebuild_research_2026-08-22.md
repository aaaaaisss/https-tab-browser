# YouTube横動画広告遮断の根本再構築調査

調査日: 2026-08-22

## 実機事実と差分監査

ユーザー確認済みの広告遮断状態はコミット`a994015`であり、Shortsスポンサー動画と横動画広告が消えていた。`a994015`から現HEADまでのWebView再生経路差分は、次のSABR関連実験だけである。

| コミット | 追加内容 | 実機結果 |
| --- | --- | --- |
| `1c06e4d` | Brave公式`brave-yt-sabr-fix`を攻めた広告遮断モードでdocument-start注入 | 横動画広告が再出現 |
| `922ed5f` | SABR再試行に広告なしフラグを渡すため`Object.assign`を補正 | 横動画広告の再出現は解消せず |

この二つは`a994015`の広告が消えていた再生経路に存在しない。したがって、SABR対策およびoutbound player request補正を完全に撤回して、広告が消えていた`a994015`の経路へ戻すことが根本修正の第一条件である。PiPの実映像比率追従は`fae067a`で実装されており、SABR実験と独立しているため維持できる。

## uBlock Origin現行YouTube規則の比較

2026-08-22時点のuAssets `filters.txt`を取得・抽出した。横動画の広告遮断で重要な規則は以下である。

| 経路 | uBlock規則の目的 | ねこぶらうざの再構築方針 |
| --- | --- | --- |
| `/youtubei/v1/player`、`get_watch`、playlist | `adPlacements`と`adSlots`を`no_ads`に置換 | 現行のdocument-start response sanitizerがplayer/get_watch/playlistで同じキーを除去する。安定版の軽量文字列置換を維持する。 |
| 初期player response | `ytInitialPlayerResponse.playerAds`、`adPlacements`、`adSlots`をundefined化 | 現行のdocument-start property hookを維持する。 |
| Shorts response | `reelWatchSequenceResponse.entries`内の`adClientParams.isAd`をprune | 現行のShorts専用配列除去を維持する。実機で広告消去を確認済み。 |
| `initplayback` TV client | `oad`付きTVHTML5の初期再生要求を遮断 | mobile System WebViewでは通常該当しない。一般的なgooglevideo遮断は動画再生を壊すため導入しない。 |

## 不採用とする再生干渉

YouTubeの広告を表示しない場合に待機を短縮するSABR `backoffTimeMs`の改変は、広告なし状態が維持できない実機結果となった。待機短縮のために再生sessionを再取得する処理は、広告遮断という最優先要件に反するため撤回する。

`Object.assign`をhookして`isInlinePlaybackNoAd`を挿入する処理も、横動画広告が再出現した状態で組み合わされており、現在のSystem WebView/YouTubeクライアントでは安定な追加層と判定しない。撤回する。

## 再構築方針

1. SABR待機回避scriptletおよびoutbound player request補正と、そのhandler・resource読込を完全に削除する。
2. 広告が消えていた`a994015`のBrave network/cosmetic/trusted scriptlet/redirect、AdGuard Base・日本語・Brave Specific、YouTube player response sanitizer、Shorts response pruneを正確に維持する。
3. user filterには任意scriptlet権限を与えず、組込み・信頼済みリストだけをdocument-startで実行する境界を維持する。
4. PiPの実映像比率追従とYouTube PiP解除はSABR変更と分離して維持する。

## 参照

1. https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt
2. https://github.com/uBlockOrigin/uAssets/blob/master/filters/filters.txt
3. https://github.com/brave/adblock-rust
4. https://github.com/brave/adblock-resources/blob/master/resources/brave-yt-sabr-fix.js
