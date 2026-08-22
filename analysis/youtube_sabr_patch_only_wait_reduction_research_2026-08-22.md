# YouTube広告遮断を維持するSABR待機短縮の調査

調査日: 2026-08-22

## 実機事実

安定版`a994015`の多層遮断では、横動画の広告とShortsスポンサー動画を非表示化できた。一方で横動画は約15秒の待機を示す。SABR公式scriptletをそのまま注入した版と、player request補正版では横動画広告が再出現した。このため再生sessionを作り直す処理は広告遮断と両立しない。

## 待機の仕組み

YouTubeのSABR制御応答には`backoffTimeMs`が含まれる。この値はサーバーが次の映像データ取得まで待つようクライアントへ通知する。広告が表示される場合は広告がこの待機を隠すが、広告を遮断すると待機だけがspinnerとして見える。外部調査では15秒広告なら約12秒、複数広告なら十数秒の待機が起き得ると説明されており、ユーザー実機の約15秒と整合する。[1]

## 分離すべきBrave公式scriptletの二つの動作

| 動作 | 内容 | 実機判定 |
| --- | --- | --- |
| `patchBackoffField` | SABRの小さなprotobuf制御応答にある`backoffTimeMs`を、同じvarint長のまま50〜150msへ書き換える。映像chunkは1,000 bytesで打ち切り、無加工で返す。 | 試す価値がある。既存sessionを再取得しない。 |
| `forceFreshSession` | `cancelPlayback()`と`loadVideoById()`で初回再生sessionを一度だけ再取得する。 | 横動画広告を再出現させたため不採用。 |

## 段階的な実装方針

1. 広告が消える安定経路`a994015`を基準にする。
2. Brave公式resourceをそのまま注入せず、`patchBackoffField`とstream teeの安全策だけを抽出した**patch-only** scriptを追加する。
3. `forceFreshSession`、`isInlinePlaybackNoAd`、`Object.assign`、`JSON.stringify`へのhookは含めない。player request・レスポンス・再生sessionを変更しない。
4. `googlevideo.com`かつ`sabr=1`のfetchだけを対象にする。media chunkが1,000 bytes以上ならreaderをcancelして元streamを返し、小さな制御応答だけを再発行する。解析失敗時も元responseを返す。
5. 通常モードには適用せず、攻めた広告遮断モードでのみ有効化する。広告再出現時は攻めたモードをOFFにするだけで安定経路へ戻る。

## リスク評価

patch-onlyでもYouTubeのSABR protobuf形式が変われば効果が出ない、または再生に影響する可能性はある。ただしsession再取得をしないため、前二版で確認された広告再割当て経路を作らない。待機短縮と広告遮断を同時に試すべき最小変更はこの方式である。

## 参照

[1] https://iter.ca/post/yt-adblock/
[2] https://github.com/brave/adblock-resources/blob/master/resources/brave-yt-sabr-fix.js
