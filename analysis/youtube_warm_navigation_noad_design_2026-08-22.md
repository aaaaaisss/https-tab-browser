# YouTube warm navigation向け広告なしplayer request設計

調査日: 2026-08-22

## 目的

広告が消えている`f37150b`の安定遮断を維持し、YouTubeの再生sessionを再取得せずに、クライアントが発行するwarm navigationの`/youtubei/v1/player` requestへ`isInlinePlaybackNoAd:true`を挿入する。広告slotなしのGVS URLが返ればSABR初期待機を起こさない。[1]

## 過去の失敗との差分

前回の失敗は、Brave resourceの`forceFreshSession()`が`cancelPlayback()`と`loadVideoById()`を使い、動画を広告slotが割り当て得る新sessionへ作り直した点にあった。今回の設計では、sessionのcancel、reload、`loadVideoById()`、`ytInitialPlayerResponse`消去を一切行わない。

| 要素 | 過去の実験 | 今回の条件付き設計 |
| --- | --- | --- |
| session | 新規sessionを強制 | 既存sessionを維持 |
| 初期ページ | 再読込の対象になり得る | cold navigationは変更しない |
| player request | Object.assignだけの補正 | JSON.stringifyを先に補正し、Object.assignを補助経路にする |
| 対象 | SABR backoff検出後 | 次のwarm player request生成前 |
| SABR | backoff検出後にreload | 既存patch-onlyを維持 |

## 実装の条件

1. **攻めた広告遮断モードだけ**でdocument-start登録する。
2. `JSON.stringify`の返り値で`contentPlaybackContext`を持つrequest JSONだけに`isInlinePlaybackNoAd:true`を挿入する。
3. YouTubeのlocker scriptが`JSON.stringify`を固定するA/Bテストに備え、先にdocument-startで登録する。補助として、同じbodyを生成する`Object.assign`の戻り値だけを限定補正する。
4. ad sanitizerとSABR patch-onlyより前に登録し、YouTube本体のscriptより先に実行する。
5. `cancelPlayback`、`loadVideoById`、初期response削除、URL遷移、Cookieの操作は禁止する。
6. 既存videoが再生開始済み、Shorts、live、cold navigationを意図的に再読み込みしない。

## 効果と限界

この経路はクライアントがplayer requestを発行するwarm navigationでのみ、広告slotとGVS backoffの発生前に作用する。URLを直接開いたcold navigationでは、サーバー埋込みの`ytInitialPlayerResponse`を使うため補正できない。初期responseを消して強制する案はlive破損・プレイヤーflash・読み込み悪化の公表済み副作用があるため採用しない。[1]

## 参照

[1] https://iter.ca/post/yt-adblock/
[2] https://raw.githubusercontent.com/gorhill/uBlock/master/src/js/resources/scriptlets.js
