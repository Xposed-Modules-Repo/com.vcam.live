plugins {
    id("com.android.application")
}

android {
    namespace = "com.vcam.live"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vcam.live"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            val store = System.getenv("KEYSTORE_B64")
            if (!store.isNullOrBlank()) {
                storeFile = file(System.getenv("KEYSTORE_PATH") ?: "$rootDir/release.keystore")
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val hasSigning = !System.getenv("KEYSTORE_B64").isNullOrBlank()
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        checkDependencies = true

        // 启用死代码、冗余代码与无用资源检测
        enable += setOf(
            "UnusedResources",
            "UnusedIds",
            "UnusedNamespace",
            "ObsoleteSdkInt",
            "StringFormatMatches",
            "VectorRaster"
        )

        // 遇到严重问题直接作为错误中断
        error += setOf(
            "UnusedResources",
            "UnusedIds",
            "ObsoleteSdkInt",
            "UnusedNamespace"
        )

        lintConfig = file("lint.xml")
        htmlReport = true
        xmlReport = true
        textReport = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Xlint:-processing",
            "-Xlint:-serial"
        )
    )
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
