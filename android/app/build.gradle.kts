plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.carnison.tunnel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carnison.tunnel"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("TN_VCODE") ?: "2100020000").toInt()
        versionName = System.getenv("TN_VER") ?: "2100.2.0"
        ndk { abiFilters += listOf("arm64-v8a") } // the engine is built for arm64 phones
        buildConfigField("String", "DAY", "\"" + (System.getenv("TN_DAY") ?: "") + "\"")
        buildConfigField("String", "LABEL", "\"" + (System.getenv("TN_LABEL") ?: "V1.0") + "\"")
    }
    buildFeatures { buildConfig = true }

    // the interface is the same page as on Windows
    sourceSets["main"].assets.srcDirs("../../ui")

    // one permanent key (android/keys/tunnel.jks.enc, decrypted in CI) so every new APK installs over the old one
    val tnKeystore = System.getenv("TN_KEYSTORE")
    signingConfigs {
        if (tnKeystore != null) create("tunnel") {
            storeFile = file(tnKeystore)
            storePassword = System.getenv("TN_KEYPASS")
            keyAlias = "tunnel"
            keyPassword = System.getenv("TN_KEYPASS")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (tnKeystore != null) signingConfigs.getByName("tunnel") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // AdGuard's TrustTunnel Android adapter, built from source with the Tunnel split hook (see .github/workflows/build.yml)
    implementation(files("libs/trusttunnel.aar"))
    // what the adapter itself depends on (a local .aar brings no dependency list)
    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("com.github.tony19:logback-android:2.0.0")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.0")
    implementation("com.akuleshov7:ktoml-core:0.7.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
}
