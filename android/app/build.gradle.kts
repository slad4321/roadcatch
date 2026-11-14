import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties (not in git). Without it, release builds are unsigned.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "kr.gilzikim.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "kr.gilzikim.app"
        minSdk = 28
        // The Pleos Connect emulator image is API 34.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // No production server yet: an empty URL turns server lookups off.
        buildConfigField("String", "SERVER_URL", "\"\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No server in release: the map and place names come from the bundled demo assets.
            // R8 drops the unused part of the icon library (the APK is ~66 MB without it).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // server/server.py on the development machine. The Pleos emulator image has no
            // working network (no default network, 10.0.2.2 unreachable), so run
            // `adb reverse tcp:8790 tcp:8790` and reach the server through adb on loopback.
            buildConfigField("String", "SERVER_URL", "\"http://127.0.0.1:8790\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Pleos Playground SDKs
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.fused.location)
    implementation(libs.pleos.navi.helper)
    implementation(libs.pleos.speech.to.text)
    implementation(libs.pleos.text.to.speech)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
