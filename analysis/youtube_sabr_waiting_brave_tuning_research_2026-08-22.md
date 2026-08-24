# YouTube再生待機とBrave SABR対策の調査

調査日: 2026-08-22

## 実機観測

Shortsスポンサー動画はねこぶらうざの専用処理で非表示化できた。横動画の広告表示も抑えられている一方、広告を表示しない場合に動画開始まで待機が残る。これはJavaScript JSON処理の重さだけではなく、YouTubeが広告スロット時間を埋めるために返す再生待機と整合する。

## Braveの公式SABR scriptlet

Brave公式resource `brave-yt-sabr-fix.js` は、YouTube SABR（Server Adaptive Bitrate）の小さな制御レスポンスを監視する。そこに含まれるprotobufの`backoffTimeMs`が500msを超える場合、50〜150msに書き換える。また初回再生前だけ`isInlinePlaybackNoAd`をセットし、`cancelPlayback()`と`loadVideoById()`で広告スロットを持たない新しいSABR sessionを一度だけ要求する。

> Braveのsourceコメントは、広告遮断後にも残る`backoffTimeMs`が4〜16秒のspinnerを起こすと説明している。実際の広告はこの待機時間を埋めるため、広告が非表示でも待機が露出する。

重要な安全策は次のとおり。

| 防御 | 内容 |
| --- | --- |
| 対象限定 | `googlevideo.com`かつ`sabr=1`のfetchだけ。 |
| stream保護 | 1,000 bytes以上のmedia chunkはteeしたまま無加工で通す。小さな制御応答だけを再発行する。 |
| Premium除外 | Premiumロゴを遅延確認して処理対象外にする。 |
| 再読込制限 | 動画IDごとに一度だけ。再生が1秒を超えた後はsession再取得しない。 |
| 失敗時 | stream tee・解析が失敗した場合は元のresponseを返す。 |

## 標準リストでの状態

Brave Specificには`brave-yt-sabr-fix` resourceが含まれるが、現行の規則は次のようにコメントアウトされている。

```text
! Youtube sabr delay fix
! m.youtube.*##+js(brave-yt-sabr-fix)
```

つまりBrave公式ではresource自体は更新・維持される一方、全ユーザーへ標準で強制していない。ねこぶらうざでは、一般ページの安全性を維持するため、通常モードには有効化せず、YouTube広告遮断を優先する設定でのみ、対象originを限定して明示適用するのが妥当である。

## adblock-rustの注意点

Brave issue #679では、YouTube上の広範な`json-edit` scriptletが再生前広告挙動を悪化させる事例が報告された。ねこぶらうざは横動画のresponse JSON全走査をすでに軽量なキー無効化に変更済みである。SABR対策ではplayer JSONを追加解析せず、Brave公式の小さなprotobuf制御応答だけを対象にする。

## 実装候補

1. Brave resourceのSABR scriptlet本文を、`www.youtube.com`と`m.youtube.com`のdocument-start scriptとして登録する。
2. `aggressiveAdBlockingEnabled`がONかつ広告遮断ONの場合のみ実行する。通常モードの再生保護は変えない。
3. user filter listには任意JavaScript実行権限を与えない。組込みBrave resource本文だけを使用する。
4. 初回再生の一度だけsessionを再取得する挙動を維持し、再生中とPremiumを除外する。
5. WebViewで`ReadableStream.tee`が利用できない場合はno-opとなり、元responseを維持する。

## 参照

1. https://raw.githubusercontent.com/brave/adblock-resources/master/resources/brave-yt-sabr-fix.js
2. https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-specific.txt
3. https://github.com/brave/adblock-resources
4. https://github.com/brave/adblock-rust/issues/679
5. https://github.com/brave/adblock-rust
