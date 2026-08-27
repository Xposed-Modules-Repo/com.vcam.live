plugins {
    id("com.android.application")
}

android {
    namespace = "com.vcam.live"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vcam.live"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    // Modern Xposed API used by Vector framework
    compileOnly("io.github.libxposed:api:102.0.0")

    // Material 3 UI for the control page
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // SRT transport library
    implementation("io.github.thibaultbee.srtdroid:srtdroid-core:1.9.5")
}
