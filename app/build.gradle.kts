plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.awpulent.flashrev"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awpulent.flashrev"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The app deliberately uses only the Android framework + Kotlin stdlib:
    // no AndroidX, no third-party runtime libraries. This keeps the APK tiny
    // and the build fast.
    testImplementation("junit:junit:4.13.2")
}
