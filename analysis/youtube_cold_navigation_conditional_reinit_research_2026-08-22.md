# YouTube cold navigation初期待機の条件付き再初期化調査

調査日: 2026-08-22

## 安定復帰点

コミット`f682071`はタグ`stable-youtube-wait-f682071`としてGitHubへ固定した。広告遮断と待機短縮を実機確認済みの復帰点であり、以降のcold navigation実験に問題が出た場合はこのコミットへ戻す。

## 初期responseについて確認したこと

uBlock Origin開発者の説明では、ページを最初に開いた時にはfetchでの`playerResponse`が存在せず、サーバー埋込みの`ytInitialPlayerResponse`が使われる。このため、初期responseの`playerAds`、`adPlacements`、`adSlots`を削除する規則が初期広告表示を防ぐ。[1]

ねこぶらうざはすでにdocument-startで同じ三つのkeyを`ytInitialPlayerResponse`から削除している。これは広告表示を防ぐが、GVS URLが広告slotに対応するSABR backoffを既に含む場合、待機自体は消せない。

## 再初期化の必要条件

`isInlinePlaybackNoAd:true`をクライアント発行のplayer requestに設定すれば、広告slotとSABR backoffを返させない。[2] cold navigationでは最初のresponseがサーバー生成のため、これを使うにはクライアントplayer requestを一度発行させる必要がある。

過去の無条件SABR再初期化は、バックオフ検出後に任意の初期videoへ`cancelPlayback()`と`loadVideoById()`を行い、広告が再出現した。原因はsession生成を広く実施したことと、request body補正の網羅性が不足していたことにある。

## 段階的に試せる限定経路

以下をすべて満たす時だけ、一回だけreinitを試す。

| 条件 | 理由 |
| --- | --- |
| 攻めた広告遮断モード | 通常モードの安定性を保つため。 |
| `ytInitialPlayerResponse`に広告keyがあった | server-generated広告slotの可能性が高いcold navigationだけに限定するため。 |
| 通常のwatch URLとVOD | Shorts・埋込・liveを対象外にするため。 |
| `video.currentTime <= 0.5`かつ未再生 | 再生中動画を中断しないため。 |
| warm player request hookが先に登録済み | 再作成されるplayer requestへ`isInlinePlaybackNoAd`を入れるため。 |
| video IDにつき一回だけ | 再読込loopを防ぐため。 |

この限定reinitは、広告keyが存在しない通常のcold navigation、すでに広告なしのvideo、live、Shortsには作用しない。期待効果は、広告slot付きの初期GVS sessionを広告なしplayer requestへ置き換え、SABR待機を直接除去することである。

## リスクと撤退条件

初期responseを無条件に削除する方式はlive破損・player flash・遅延増大という副作用が公表されており採用しない。[2] 条件付きreinitでもplayer flashや広告再出現が起き得るため、問題が出ればタグ`stable-youtube-wait-f682071`へ戻す。通常モードでは機能を一切登録しない。

## 参照

[1] https://github.com/uBlockOrigin/uBlock-issues/discussions/2999
[2] https://iter.ca/post/yt-adblock/
