plugins {
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.application)
}

android {
    namespace = "belowevolve.tvhome"
    compileSdk = 37
    buildFeatures { compose = true }

    defaultConfig {
        applicationId = "belowevolve.tvhome"
        minSdk = 28
        targetSdk = 34
        versionCode = 41
        versionName = "0.37.0"
        testInstrumentationRunner = "belowevolve.tvhome.LauncherRegressionRunner"
    }

    buildTypes {
        release {
            // Keep the installed launcher and its signature-protected film.mov palette compatible.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
}
