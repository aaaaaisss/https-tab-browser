# ねこぶらうざ GitHub・Release運用ガイド

この文書は、ねこぶらうざをGitHubだけで管理し、**固定署名のRelease APK**を安全に作るための現行手順です。Android Studioは必要ありません。アプリの実装変更に着手するAI・作業者は、最初に[`AI_HANDOFF.md`](AI_HANDOFF.md)を読み、この文書をビルド・配布の基準として使ってください。

> **旧方式は使用しません。** `assembleDebug`、debug APK、`main`へのpushだけを契機にした配布は、現行の上書き更新可能な固定署名Release APK運用ではありません。

## 現在の運用基準

| 項目 | 現行の基準 |
| --- | --- |
| リポジトリ | `aaaaaisss/https-tab-browser`（Private） |
| 通常の作業ブランチ | `rebuild/121e47b-four-pillars` |
| 安定復帰タグ | `stable-youtube-wait-f682071` |
| ビルドワークフロー | `.github/workflows/android-ci.yml` |
| 配布物 | `HTTPS-Tab-Browser-release-apk` artifact 内の `app-release.apk` |
| 署名 | GitHub Actionsの既存secretによる固定署名。鍵・パスワード・`applicationId`は変更しない。 |
| CI検証 | Node回帰検査、Rust test、Kotlin unit test、lint、Release APK生成 |

## 開発から配布までの手順

変更は小さく、機能単位でコミットします。アプリの実行コードを変更した場合は、先に統合回帰検査を実行します。

```bash
cd /home/ubuntu/https-tab-browser-github
node tools/verify_all.js
git diff --check
git status --short
```

問題がなければ、必要なファイルだけをコミットし、作業ブランチへpushします。

```bash
git add <対象ファイル>
git commit -m "変更内容を表す短いメッセージ"
git push origin rebuild/121e47b-four-pillars
```

push後、固定署名Release APKを明示的にクラウドビルドします。途中コミットごとに自動配布しないため、利用者が確認する版だけを意図して作れます。

```bash
gh workflow run android-ci.yml \
  --repo aaaaaisss/https-tab-browser \
  --ref rebuild/121e47b-four-pillars

gh run list \
  --repo aaaaaisss/https-tab-browser \
  --workflow android-ci.yml \
  --branch rebuild/121e47b-four-pillars \
  --limit 1 \
  --json databaseId,headSha,status,conclusion,url \
  --jq '.[0]'
```

Actionsが成功したらartifactを取得し、APKのSHA-256を確認します。

```bash
gh run download RUN_ID \
  --repo aaaaaisss/https-tab-browser \
  --name HTTPS-Tab-Browser-release-apk \
  --dir release/COMMIT

sha256sum release/COMMIT/app-release.apk
```

## GitHub画面だけで取得する場合

GitHubの対象Actions実行ページを開き、成功した`Android CI`の下部にあるArtifactsから`HTTPS-Tab-Browser-release-apk`をダウンロードします。ZIPを展開すると`app-release.apk`があります。APKとともに報告されたSHA-256と一致することを確認してからインストールしてください。

同じ署名鍵と同じ`applicationId`を使うRelease APKであれば、Android端末では既存のねこぶらうざへ**上書き更新**できます。

## CIが確認する内容

| 段階 | 内容 |
| --- | --- |
| Node回帰検査 | URLバー、候補、ホーム復帰、YouTube広告response sanitization、warm player request、SABR patch-only、Brave resource登録を検査 |
| Rust検査 | Brave adblock-rust JNI実装のtestを実行 |
| ネイティブビルド | `arm64-v8a`と`armeabi-v7a`向けの広告遮断ライブラリを生成 |
| Android検査 | Kotlin unit test、lint、署名付きRelease APK生成 |
| 配布 | 成功時だけRelease APKを14日間artifactとして保持 |

## 失敗時の扱い

Actionsが失敗した場合、失敗したcommitを配布しません。まずActionsのログと`android-validation-reports` artifactを確認し、原因を一つずつ修正します。YouTube、PiP、広告遮断、WebView hostを同時に変更して原因を曖昧にしてはいけません。

ローカルの調査物、ダウンロード済みAPK、ActionsログはGitの管理対象外です。コミット対象は実装、必要な回帰検査、引継ぎ資料だけに限定してください。

## 絶対に変更しないもの

1. 署名鍵・署名secret・`applicationId`・ワークフローのartifact名を、理由なく変更しない。
2. `stable-youtube-wait-f682071`を削除・上書きしない。
3. `cancelPlayback()`＋`loadVideoById()`を使うYouTube session再取得を復活させない。
4. `ytInitialPlayerResponse`を削除して再読込を強制しない。
5. GitHub Actionsで成功していないAPKを配布版として扱わない。

詳細な機能上の不変条件、主要ファイル、サイズ削減の境界は[`AI_HANDOFF.md`](AI_HANDOFF.md)を参照してください。
