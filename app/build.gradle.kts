plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "belowevolve.airremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "belowevolve.airremote"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
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
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Air Remote Dev")
        }
        release {
            resValue("string", "app_name", "Air Remote")
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
