# PiPアスペクト比・YouTube広告遮断の再監査

調査日: 2026-08-22

## PiPアスペクト比

現行実装はfullscreen custom viewの画面上の枠（端末を縦持ちしている場合は縦長）からPiPの`setAspectRatio()`を算出している。そのため、横長動画を端末縦持ちで全画面再生してPiPへ移すと、動画そのものは横長でもPiPコンテナが縦長になり、上下に黒帯が生じる。

AndroidのPiP比率は幅÷高さであり、2.39:1から1:2.39までに収める必要がある。sourceRectHintは遷移元の画面位置、aspectRatioは実コンテンツの比率として別々に指定できる。

### 採用する実装

1. WebViewへ安全なread-only JavaScript bridgeを設ける。
2. `video.videoWidth`と`video.videoHeight`を`loadedmetadata`、`resize`、`playing`で取得する。
3. 最大の再生可能video要素の実映像サイズだけをActivityへ通知する。通知は同一サイズを重複送信しない。
4. MainActivityは実映像サイズを保持し、PiP開始前に`Rational(videoWidth, videoHeight)`を使う。sourceRectHintは引き続きfullscreen custom viewの画面座標を使用する。
5. 実映像サイズ未取得時だけ従来のview外枠比率をフォールバックとする。

## YouTube広告遮断の監査

| 構成 | YouTubeへの寄与 | 判定 |
| --- | --- | --- |
| 既存AdGuard 101 optimized | `pagead`、`player/ad_break`、`get_midroll_`、動画広告UIを含む。 | 基礎として維持する。 |
| 既存AdGuard 7 optimized | 日本サイト向けscriptletが中心。YouTube広告の直接規則は少ない。 | 維持する。 |
| Brave Specific | YouTube navigation、theater、playback speed、バックグラウンド再生の補助scriptletを含むが、広告除去規則は主目的ではない。 | YouTube機能補助として採用価値がある。 |
| uBlock filters | YouTubeの`youtubei/v1/player`・`get_watch`・Shortsへの`adPlacements`、`adSlots`、`playerAds`除去scriptletを継続的に更新している。 | YouTube広告除去の主追加候補。ただしuBlock scriptlet resourceをBrave resourceだけで解決できないため、リスト全体をtrusted scriptletとして読み込まない。 |
| AdGuard Base optimized | EasyList + AdGuard English filterであり、汎用ネットワーク遮断を大幅に増強する。 | 個人用の攻めた構成として標準追加する。 |

## エンジン比較

adblock-rustはABP/AdGuard/uBlock互換ネットワーク・cosmetic・resource replacement・scriptletの評価をネイティブで実行する。Android WebViewで同等の既存OSSエンジンに交換しても、WebViewのrequest interceptionとdocument-start APIの制約は残る。

AdGuard CoreLibは専有ライセンスであり、個人用アプリへの組込みを前提とした無制限OSS代替にはならない。uBlock Originの完成したYouTube対応は、ブラウザ拡張固有のwebRequest/HTML filtering/scriptlet resource集合にも依存する。そのため、ねこぶらうざでは既存Brave engineを維持し、uBlockのYouTubeルールを同じ機能へ安全に翻訳した専用補助フィルタとJavaScriptを組み合わせるのが最も実装可能である。

## 多層遮断の採用案

1. Brave `adblock-rust`へAdGuard Base optimizedとBrave Specificを追加し、ネットワーク・cosmetic・redirect処理を増やす。
2. YouTube専用の組込みルールを作る。`youtubei/v1/player`・`get_watch`・playlist・Shortsレスポンスから`adPlacements`、`adSlots`、`playerAds`、Shorts広告指標をdocument-startで除去する。
3. 既存の攻めたモードで、YouTube media/subdocumentの保護を外してBraveネットワーク規則を全評価する。通常モードは再生保護を維持する。
4. WebViewが動画requestを安全に任意再発行できないため、uBlockの`$replace`ネットワーク規則をそのまま有効化しない。ページ内のfetch/XHR応答をdocument-startで安全に書き換える。
5. 外部ユーザー追加リストにJavaScript実行権限を与えない。組込みでレビュー済みのYouTube補助スクリプトだけを追加する。

## 参照

1. https://developer.android.com/develop/ui/views/picture-in-picture
2. https://developer.android.com/reference/android/app/PictureInPictureParams.Builder#setAspectRatio(android.util.Rational)
3. https://github.com/brave/adblock-lists
4. https://github.com/brave/adblock-lists/blob/master/brave-lists/brave-specific.txt
5. https://filters.adtidy.org/android/filters/2_optimized.txt
6. https://github.com/uBlockOrigin/uAssets/blob/master/filters/filters.txt

> 重要: YouTube広告はGoogleが同一配信基盤を使い、広告だけをネットワークで100%完全に分離できない場面がある。再生を壊さない通常モードと、最大遮断の攻めたモードを分け、JSON/XHR応答内の広告メタデータを先に消すことが最も現実的である。
