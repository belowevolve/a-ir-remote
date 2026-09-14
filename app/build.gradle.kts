plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "dev.air.remote"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.air.remote"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("debug").java.directories += "build/generated/java/generateDebugProto/java"
        getByName("release").java.directories += "build/generated/java/generateReleaseProto/java"
    }

    packaging {
        jniLibs.keepDebugSymbols += "**/libandroidx.graphics.path.so"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(".signing/release.p12")
            val passwordFile = rootProject.layout.projectDirectory.file(".signing/password")
            val password = providers.fileContents(passwordFile).asText.orNull?.trim()
            storePassword = password
            keyPassword = password
            keyAlias = "air-remote"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.protobuf.javalite)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Protobuf Lite uses Unsafe for Android message layouts. Permit it explicitly
    // on the desktop test JVM; Android builds and other JVM warnings are unaffected.
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
}
