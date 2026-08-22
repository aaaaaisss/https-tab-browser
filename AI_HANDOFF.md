# ねこぶらうざ 開発引継ぎガイド

この文書は、ねこぶらうざ（Android WebViewベースのブラウザ）を別のAI、または軽量モデルが安全に継続開発するための最小限かつ実行可能な手引きです。変更前に必ずこの文書と対象ファイルを読み、ここにある不変条件を破らないでください。

## 現在の基準

| 項目 | 内容 |
| --- | --- |
| リポジトリ | `aaaaaisss/https-tab-browser`（Private） |
| 作業ブランチ | `rebuild/121e47b-four-pillars` |
| 安定復帰タグ | `stable-youtube-wait-f682071` |
| アプリ名 | ねこぶらうざ |
| ビルド方式 | GitHub Actions `android-ci.yml` による固定署名Release APK |
| 最低SDK / target SDK | API 26 / API 35 |
| 言語 | Kotlin 2.0.21、Rust（Brave adblock-rust 0.13.3 JNI） |

## 絶対に守る不変条件

YouTubeの横動画広告遮断、Shorts広告遮断、PiP、全画面再生が実機確認済みです。以下を変更・追加してはいけません。

1. `cancelPlayback()`と`loadVideoById()`を用いたYouTubeのsession再取得を実装しない。広告が再出現した実機履歴がある。
2. `ytInitialPlayerResponse`そのものを削除・無効化して再読込を強制しない。live再生や初期描画を壊す恐れがある。
3. Brave adblock-rustをJava/Kotlinの独自ABPパーサで置き換えない。ネットワーク規則はネイティブエンジンで評価する。
4. WebViewをCompose `AndroidView`へ戻したり、全画面custom viewをComposeの親へ再親子化しない。native host構成は動画表示の安定化策である。
5. 署名鍵・ワークフロー名・applicationIdを変更しない。上書き更新できる固定署名APKを保つためである。

## 主要ファイルと責務

| ファイル | 変更対象になる機能 |
| --- | --- |
| `ui/BrowserViewModel.kt` | タブ、ホーム、戻る、アドレス入力、候補、設定の状態遷移 |
| `ui/BrowserScreen.kt` | Compose UIとWebView registryの接続、IME、戻る、全画面、PiP |
| `ui/BrowserControls.kt` | URLバー、IME submit、候補一覧、タブバー、favicon、ホームグリッド |
| `ui/BrowserSheets.kt` | 設定、広告フィルタ、履歴、ブックマーク、ライセンス画面 |
| `web/BrowserWebView.kt` | WebView設定、Brave network/cosmetic/scriptlet、YouTube広告遮断、SABR、PiP |
| `data/BrowserModels.kt` | 保存するUIモデル・設定値 |
| `data/BrowserRepository.kt` | DataStoreの保存・復元と旧設定値の互換性 |
| `data/AdBlocker.kt` | 既定フィルタの取得・更新 |
| `data/BraveAdBlockEngine.kt` | Rust JNIを通したfilter compile・network decision |
| `MainActivity.kt` | native WebView host、動画全画面、PiP画面比率 |

## 現在の設定UI

設定では暗色化と広告ブロックをそれぞれ二択で表示する。

| 表示 | 保存値 | 意味 |
| --- | --- | --- |
| 暗色化: normal | `forceDarkPages=true`, `forceDarkVideoPages=false` | 通常ページだけを暗色化する既定の安定設定 |
| 暗色化: high | `forceDarkPages=true`, `forceDarkVideoPages=true` | 動画サイトも暗色化対象に含める |
| 広告ブロック: normal | `adBlockingEnabled=true`, `aggressiveAdBlockingEnabled=false` | 再生互換性を保つ既定の遮断 |
| 広告ブロック: high | `adBlockingEnabled=true`, `aggressiveAdBlockingEnabled=true` | YouTube等で遮断率を優先する設定 |

保存形式は既存ユーザーとの互換性のためbooleanのままである。設定をenumなどへ変更する際は、`BrowserRepository.kt`で必ず旧DataStore値からの移行を実装する。

## URLバー・候補・ホームの仕様

1. URLバーを開くと、Google検索ページでは現在の検索語を残し、その語で履歴・Google候補を直ちに取得する。
2. 候補は最大6件で、`SuggestionPanel`の`reverseLayout=true`により、ViewModel上の先頭候補が画面下端に置かれる。下側ほど優先度が高い。
3. IMEの検索確定は`AddressBar`から最新の入力文字列を明示的に渡す。`BrowserScreen`はその値で`prepareNavigation`を呼ぶ。
4. IMEを閉じたとき、URLバーの編集状態と候補パネルを同時に閉じる。
5. ホームから開いたブックマーク・検索・URLは、WebView履歴が尽きた戻る操作で独自ホームへ戻る。戻る先をChromium履歴に保存してはいけない。

## 変更後の必須検査

対象がUIのみでも、以下を実行する。

```bash
cd /home/ubuntu/https-tab-browser-github
node tools/verify_all.js
git diff --check
```

`verify_all.js`は、URLバー・候補・ホーム復帰、YouTube広告response sanitization、warm player request、SABR patch-only、Brave resource登録をまとめて検査する。GitHub ActionsもAPK生成前にこの統合検査を必ず実行する。

変更は機能単位でコミットし、`rebuild/121e47b-four-pillars`へpushする。続けて固定署名Release APKを必ず作成する。

```bash
gh workflow run android-ci.yml --repo aaaaaisss/https-tab-browser --ref rebuild/121e47b-four-pillars
gh run list --repo aaaaaisss/https-tab-browser --workflow android-ci.yml --branch rebuild/121e47b-four-pillars --limit 1 --json databaseId,headSha,status,conclusion,url --jq '.[0]'
```

成功したartifactは、必ずAPKのSHA-256とともに報告する。

```bash
gh run download RUN_ID --repo aaaaaisss/https-tab-browser --name HTTPS-Tab-Browser-release-apk --dir release/COMMIT
sha256sum release/COMMIT/app-release.apk
```

## 変更を小さく保つための規則

単一の不具合修正で、WebView・PiP・広告ブロック・暗色化を同時に再設計しない。まず状態遷移、次にUI接続、最後に動画・広告遮断の順で扱う。YouTubeの実機確認が必要な変更には、どの再生経路へ影響するかをコードコメントとコミットメッセージに記録する。

ローカルの`analysis/`、`reference/`、`release/`はGit管理外である。後続AIは差分確認にこれらを含めず、再利用すべき結論だけを本書または追跡対象の文書へ移す。

## サイズ・コード削減の安全境界

Release APKは、広告遮断のRustネイティブライブラリ（arm64-v8aとarmeabi-v7a）、オフライン初期適用用のAdGuardフィルタ、Brave scriptlet resource、Compose/Kotlinコードで構成される。容量削減は可能だが、次の区別を守る。

| 区分 | 対象 | 扱い |
| --- | --- | --- |
| 安全候補 | `drawable/ic_browser_cat.png`のような、manifest・XML・Kotlinから未参照と確認済みの重複画像 | 参照検索とReleaseビルド後にのみ削除できる。効果は小さい。 |
| 要検証候補 | ReleaseのR8最適化（`isMinifyEnabled`）、resource shrink、ABI別APK | 容量効果は期待できるが、WebView/Compose/JS bridge/JNI/署名更新を実機確認する専用フェーズが必要。通常の機能修正と同時に実施しない。 |
| 削除禁止 | `libhttps_browser_adblock.so`、AdGuard標準フィルタ、Brave resource、YouTube/PiP/全画面の既存安全対策 | 広告遮断、初回オフライン動作、動画再生、PiP、対応端末を直接失うため、容量だけを理由に削除しない。 |

現在のuniversal APKは`arm64-v8a`と`armeabi-v7a`を同梱する。ABI別の配布はAPK単体の容量を減らせるが、誤ったABIのAPKを利用者が選ぶリスクと配布運用の複雑さが増すため、個人利用の単一配布では既定採用しない。

> サイズ・コード削減は、未参照資産の削除を除き、安定化作業と混在させない。必ず専用ブランチ、固定署名の試験APK、横動画・Shorts・PiP・ホーム復帰の実機確認を通してから採用する。
