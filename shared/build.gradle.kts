plugins {
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
}

android {
    namespace = "belowevolve.shared"
    compileSdk = 37
    buildFeatures { compose = true }
    defaultConfig { minSdk = 28 }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
}
