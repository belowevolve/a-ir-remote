import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class PrepareVoiceModel : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val archive = outputDirectory.file("voice/ru-small-0.22.zip").get().asFile
        val expected = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
        fun checksum(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (archive.isFile && checksum(archive) == expected) return
        archive.parentFile.mkdirs()
        val temporary = File(archive.parentFile, "ru-small-0.22.zip.part")
        try {
            val connection = URI("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip").toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.getInputStream().use { input -> temporary.outputStream().use(input::copyTo) }
            check(checksum(temporary) == expected) { "Russian voice model checksum mismatch" }
            Files.move(temporary.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

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
        versionCode = 79
        versionName = "0.1.78"
        resValue("string", "app_name", "Air Remote")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    flavorDimensions += "assistant"
    productFlavors {
        create("standard") { dimension = "assistant" }
        create("voice") {
            dimension = "assistant"
            proguardFiles("proguard-voice.pro")
        }
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
            ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // CI builds without secrets; a separate job signs the finished APKs.
            signingConfig = if (providers.gradleProperty("unsignedRelease").isPresent) null else signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

val prepareVoiceModel = tasks.register<PrepareVoiceModel>("prepareVoiceModel") {
    outputDirectory.set(layout.buildDirectory.dir("generated/voiceAssets"))
}

androidComponents.onVariants(androidComponents.selector().withFlavor("assistant" to "voice")) { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareVoiceModel, PrepareVoiceModel::outputDirectory)
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.outputs.forEach { output ->
        val edition = if (variant.productFlavors.any { it.second == "voice" }) "-Voice" else ""
        output.outputFileName.set(output.versionName.map { "Air-Remote$edition-$it.apk" })
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
    "voiceImplementation"("com.alphacephei:vosk-android:0.3.75")
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Protobuf Lite uses Unsafe for Android message layouts. Permit it explicitly
    // on the desktop test JVM; Android builds and other JVM warnings are unaffected.
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
}
