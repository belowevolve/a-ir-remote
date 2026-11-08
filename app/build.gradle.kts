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
        testInstrumentationRunner = "belowevolve.airremote.InputRegressionRunner"
        versionCode = 59
        versionName = "0.1.58"
        resValue("string", "app_name", "Air Remote")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
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

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.outputs.forEach { output ->
        output.outputFileName.set(output.versionName.map { "Air-Remote-$it.apk" })
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
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.protobuf.javalite)
    implementation(libs.zxing.embedded)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Protobuf Lite uses Unsafe for Android message layouts. Permit it explicitly
    // on the desktop test JVM; Android builds and other JVM warnings are unaffected.
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
}
