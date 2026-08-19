# GitHub だけで Android APK を作る手順

このフォルダは、GitHub に置くだけで Android アプリをクラウドビルドできる構成です。Android Studio、ローカル SDK、ローカル Gradle は不要です。

> 重要: ZIP ファイルそのものを GitHub リポジトリへ置くのではありません。ZIP を展開して、**中にあるファイルとフォルダ**をリポジトリ直下へ同じ構成のまま配置してください。

## 全体の順序

| 段階 | あなたが GitHub で行うこと | 成果 |
|---:|---|---|
| 1 | 空のリポジトリを作る | コードを置く場所 |
| 2 | この ZIP を展開する | アップロード可能なファイル群 |
| 3 | ファイルを同じパスへアップロードする | Android プロジェクト完成 |
| 4 | `main` ブランチへコミットする | Actions が自動開始 |
| 5 | Actions の `Android CI` を開く | lint・テスト・APK ビルド |
| 6 | Artifact をダウンロードする | `app-debug.apk` を取得 |

## 1. GitHub で空のリポジトリを作る

リポジトリ名は `https-tab-browser` にします。公開・非公開はどちらでもかまいません。初期化時に README、`.gitignore`、License を作らないでください。ファイル配置を簡単にするため、空の状態から始めます。

## 2. ZIP を展開する

配布された `https-tab-browser-github.zip` を展開します。展開後の最上位フォルダは `https-tab-browser-github` です。

このフォルダの**中身**をリポジトリのトップへ置きます。次のように、`app`、`.github`、`gradle`、各 Gradle ファイルがリポジトリの直下に並ぶ状態が正解です。

```text
https-tab-browser/                 ← GitHub リポジトリのトップ
├── .github/
│   └── workflows/
│       └── android-ci.yml
├── app/
│   ├── build.gradle.kts
│   └── src/main/
├── gradle/
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradlew.bat
```

`https-tab-browser/https-tab-browser-github/app/...` のように、フォルダが一段余分に入らないようにしてください。

## 3. ファイルを配置する

GitHub のリポジトリ画面で **Add file → Upload files** を使い、展開したフォルダ内のファイルをアップロードします。フォルダ単位で選択できない場合は、次の優先順で作成・アップロードしてください。

### 3-1. 最初に置く 8 ファイル

| GitHub 上のパス | この ZIP 内の同じパス |
|---|---|
| `settings.gradle.kts` | `settings.gradle.kts` |
| `build.gradle.kts` | `build.gradle.kts` |
| `gradle.properties` | `gradle.properties` |
| `gradlew` | `gradlew` |
| `gradlew.bat` | `gradlew.bat` |
| `gradle/wrapper/gradle-wrapper.properties` | 同じパス |
| `gradle/wrapper/gradle-wrapper.jar` | 同じパス |
| `app/build.gradle.kts` | 同じパス |

### 3-2. 次に置く Android 設定・リソース

| GitHub 上のパス | 役割 |
|---|---|
| `app/src/main/AndroidManifest.xml` | 権限、HTTPS 方針、起動 Activity |
| `app/src/main/res/xml/network_security_config.xml` | HTTP 平文通信の拒否 |
| `app/src/main/res/values/themes.xml` | Android テーマ |
| `app/src/main/res/drawable/ic_browser.xml` | アプリアイコン |

### 3-3. 最後に置く Kotlin コード

次の 10 ファイルを **ファイル名もフォルダ名も変えず** に置きます。

```text
app/src/main/java/com/example/httpsbrowser/MainActivity.kt
app/src/main/java/com/example/httpsbrowser/data/BrowserModels.kt
app/src/main/java/com/example/httpsbrowser/data/BrowserRepository.kt
app/src/main/java/com/example/httpsbrowser/data/AdBlocker.kt
app/src/main/java/com/example/httpsbrowser/ui/AppTheme.kt
app/src/main/java/com/example/httpsbrowser/ui/BrowserViewModel.kt
app/src/main/java/com/example/httpsbrowser/ui/BrowserControls.kt
app/src/main/java/com/example/httpsbrowser/ui/BrowserSheets.kt
app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt
app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt
```

## 4. GitHub Actions のファイルを置く

APK ビルドを自動化するため、次のファイルを必ず配置します。

```text
.github/workflows/android-ci.yml
```

このファイルがあると、`main` ブランチへのコミットで **Android CI** が自動開始します。ワークフローは GitHub の Ubuntu 環境で JDK 17 と Android SDK を準備し、次の順に実行します。

```text
./gradlew lintDebug testDebugUnitTest assembleDebug
```

## 5. ビルド結果を確認する

コミット後、リポジトリの **Actions** タブを開きます。

1. **Android CI** を選びます。
2. 最新の実行を開きます。
3. 緑のチェックマークが付くまで待ちます。
4. ページ下部の **Artifacts** から `HTTPS-Tab-Browser-debug-apk` をダウンロードします。
5. ダウンロードした ZIP を展開すると `app-debug.apk` があります。

## 6. 最初の確認項目

| 確認項目 | 期待する結果 |
|---|---|
| Actions が開始する | `Android CI` が実行中または成功になる |
| lint | エラーなしで終了する |
| 単体テスト | 成功する |
| APK | `HTTPS-Tab-Browser-debug-apk` Artifact が作られる |
| APK の名前 | `app-debug.apk` |

## 7. 機能の対応表

| 機能 | 主な実装ファイル |
|---|---|
| Google 固定検索・検索語選択 | `ui/BrowserViewModel.kt`、`ui/BrowserControls.kt` |
| タブと履歴の保存 | `data/BrowserModels.kt`、`data/BrowserRepository.kt` |
| HTTPS 強制・広告ブロック | `web/BrowserWebView.kt`、`data/AdBlocker.kt` |
| 下部三段バー・タブバー・ショートカット | `ui/BrowserControls.kt`、`ui/BrowserScreen.kt` |
| タブ一覧・設定・履歴・ブックマーク | `ui/BrowserSheets.kt` |
| 動画全画面・ダークモード・ダウンロード | `web/BrowserWebView.kt` |

## 8. よくある配置ミス

| 間違い | 正しい状態 |
|---|---|
| `.github` を置かない | `.github/workflows/android-ci.yml` が必要 |
| ZIP をそのままアップロードする | ZIP を展開し、中身をアップロードする |
| `app` フォルダが二重になる | リポジトリ直下に `app/build.gradle.kts` がある |
| `gradle-wrapper.jar` を置かない | `gradle/wrapper/gradle-wrapper.jar` が必要 |
| `main` 以外へコミットする | 初期設定では `main` へコミットする |

この手順だけで、GitHub 上にコードを置き、GitHub Actions で APK を作れます。
