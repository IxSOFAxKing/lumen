plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.lumen.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lumen.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"
    }

    // Signing: your own private key from environment variables (GitHub repository secrets).
    // Without one, builds are signed with a temporary debug key (installs fine, but updates then need
    // the previous version uninstalled). Never commit a keystore to the repository.
    val ksPath = System.getenv("LUMEN_KEYSTORE")
    signingConfigs {
        if (!ksPath.isNullOrBlank()) {
            create("lumen") {
                // Values are trimmed: pasted secrets often carry a stray newline/space.
                val storePw = System.getenv("LUMEN_KEYSTORE_PASSWORD")?.trim().orEmpty()
                storeFile = file(ksPath)
                storePassword = storePw
                keyAlias = System.getenv("LUMEN_KEY_ALIAS")?.trim()?.takeIf { it.isNotEmpty() } ?: "lumen"
                // PKCS12 keystores (what keytool creates by default) use the store password for the key too.
                keyPassword = System.getenv("LUMEN_KEY_PASSWORD_OVERRIDE")?.trim()?.takeIf { it.isNotEmpty() } ?: storePw
            }
        }
    }

    buildTypes {
        release {
            // R8 makes Compose much faster on TV hardware.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (!ksPath.isNullOrBlank()) signingConfigs.getByName("lumen") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi", "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi")
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("com.google.zxing:core:3.5.3")

    // Native TV interface (Jetpack Compose)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Installs the libraries' baseline profiles so Compose is compiled ahead of time on sideloaded installs.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
