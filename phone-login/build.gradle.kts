import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 手机伴侣：一个 WebView 登录番茄，把 Cookie 通过局域网发给手表。
// 和手表端用同一把 release 密钥，方便统一发版。
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeyFile = rootProject.file("keystore/shuge-release.jks")

android {
    namespace = "com.fanqie.wear.phonelogin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fanqie.wear.phonelogin"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseKeyFile.exists()) {
            create("release") {
                storeFile = releaseKeyFile
                storePassword = keystoreProps.getProperty("SHUGE_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("SHUGE_KEY_ALIAS") ?: "shuge"
                keyPassword = keystoreProps.getProperty("SHUGE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeyFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
