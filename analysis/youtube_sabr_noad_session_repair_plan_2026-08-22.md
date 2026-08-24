# YouTube SABR待機回避と広告なし再生セッションの修正計画

調査日: 2026-08-22

## 原因

直前の版はBrave公式SABR scriptletを攻めた広告遮断モードに追加した。scriptletは`backoffTimeMs`を短縮し、初回再生前に`cancelPlayback()`と`loadVideoById()`で新しいSABR sessionを要求する。ところが、この再取得時に`player.getVideoData().isInlinePlaybackNoAd = true`をセットするだけでは、YouTubeの実際の`/youtubei/v1/player` request bodyへ広告なし指定が渡らない経路がある。結果として新sessionに広告が再度割り当てられ、横動画の広告が戻った。

## 修正方針

| 層 | 修正 | 目的 |
| --- | --- | --- |
| player request | document-startで`Object.assign`を限定的にwrapし、`/youtubei/v1/player`のrequest bodyに`playbackContext.contentPlaybackContext.isInlinePlaybackNoAd=true`を追加する。 | 初回・SPA・SABR再取得のいずれでも広告なしsessionを要求する。 |
| SABR | Brave公式`brave-yt-sabr-fix`を維持する。 | すでに返された短いSABR制御応答の待機を短縮し、initial session再取得を一度だけ実施する。 |
| existing blocker | Brave network/cosmetic/scriptlet、YouTube player/Shorts応答の広告キー除去を維持する。 | request、response、DOM、networkの多層遮断を後退させない。 |

## 適用境界

この補正は、広告遮断ONかつ攻めた広告遮断モードONのYouTube originに限定する。user filter URLにJavaScript実行権限を追加しない。`Object.assign`のwrapperは`source`と`target`に`/youtubei/v1/player`および文字列bodyがあるときだけbodyを変更し、他のリクエストは元関数をそのまま実行する。

YouTubeのearly locker script対策として、`JSON.stringify`ではなく`Object.assign`をdocument-startでwrapする。この方法はYouTubeが一部A/Bテストで`JSON.stringify`をimmutable化する場合でもplayer request生成側へ到達できる。[1]

## 検証

1. scriptが`isInlinePlaybackNoAd`、`youtubei/v1/player`、`Object.assign`の3条件を持つことを静的確認する。
2. nodeの保存済み検査コードでスクリプト構文と通常request body挿入の期待値を確認する。
3. GitHub ActionsでRust native build、Android Lint、unit test、Release assembleを通す。
4. 実機では攻めた広告遮断モードONで、横動画の広告表示、開始待機、Shorts広告、PiPを確認する。

## 参照

[1] https://iter.ca/post/yt-adblock/
[2] https://github.com/brave/adblock-resources/blob/master/resources/brave-yt-sabr-fix.js
