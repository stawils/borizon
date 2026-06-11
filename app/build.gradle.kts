plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.borizon.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.borizon.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(project.findProperty("BORIZON_STORE_FILE") as String? ?: "../borizon-release.jks")
            storePassword = project.findProperty("BORIZON_STORE_PASSWORD") as String? ?: ""
            keyAlias = project.findProperty("BORIZON_KEY_ALIAS") as String? ?: "borizon"
            keyPassword = project.findProperty("BORIZON_KEY_PASSWORD") as String? ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ── Re-align native libs for 16 KB page size compatibility ──
// SQLCipher 4.5.4 ships .so files with 4 KB ELF alignment.
// Android 15+ requires 16 KB. This task patches the segments in-place.
val rootDirPath: String = rootDir.absolutePath
val buildDirPath: String = layout.buildDirectory.get().asFile.absolutePath

tasks.register("alignNativeLibs") {
    notCompatibleWithConfigurationCache("patches .so files in merged native libs dir")

    val scriptPath = rootDirPath
    val jniBasePath = buildDirPath

    doLast {
        val script = File(scriptPath, "align_elf_16kb.py")
        val jniBase = File(jniBasePath, "intermediates/merged_native_libs")
        if (!jniBase.exists()) return@doLast

        jniBase.walkTopDown()
            .filter { it.extension == "so" }
            .forEach { soFile ->
                val proc = ProcessBuilder("python3", script.absolutePath, soFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                if (proc.exitValue() != 0) {
                    throw GradleException("Failed to align ${soFile.name}:\n$output")
                }
                logger.lifecycle(output.trim())
            }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    finalizedBy("alignNativeLibs")
}

// Protobuf code generation 
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
    generateProtoTasks {
        all().forEach { it.plugins { create("java") { option("lite") } } }
    }
}

dependencies {
    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // OkHttp (web search API calls)
    implementation(libs.okhttp)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)

    // SplashScreen (invisible system splash, dismissed instantly)
    implementation(libs.splashscreen)

    // Navigation
    implementation(libs.navigation.compose)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // SQLCipher (encrypted Room database, 16KB page-aligned)
    implementation(libs.sqlcipher)

    // LiteRT-LM for on-device inference
    implementation(libs.litert.lm)
    // LiteRT GPU delegate for hardware-accelerated inference
    implementation(libs.litert.gpu)

    // Biometric
    implementation(libs.biometric)

    // ExifInterface (image orientation)
    implementation(libs.exifinterface)

    // CameraX (image capture)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Protobuf for Proto DataStore
    implementation(libs.protobuf.javalite)

    // WebView asset loading for JS skills
    implementation(libs.webkit)

    // DocumentFile for SAF-based skill import
    implementation(libs.documentfile)

    // DataStore Preferences
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.workmanager)

    // Markdown rendering (halilibo richtext — CommonMark compliant)
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui.material3)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.room.testing)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
