plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.httpsbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.httpsbrowser"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions の連番で更新順序を管理する。
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        // 端末用 APK にエミュレータ専用の x86 / x86_64 ネイティブコードを入れない。
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { keyStorePath ->
                storeFile = file(keyStorePath)
            }
            storeType = "PKCS12"
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
