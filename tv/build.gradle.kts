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
        versionCode = 58
        versionName = "0.54.0"
        testInstrumentationRunner = "belowevolve.tvhome.LauncherRegressionRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(".signing/tv-release.p12")
            val password = providers.fileContents(rootProject.layout.projectDirectory.file(".signing/tv-password")).asText.orNull?.trim()
            storePassword = password
            keyPassword = password
            keyAlias = "air-home"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            signingConfig = if (providers.gradleProperty("unsignedRelease").isPresent) null else signingConfigs.getByName("release")
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
