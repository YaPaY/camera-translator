plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.cameratranslator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.cameratranslator"
        minSdk = 24
        targetSdk = 37
        versionCode = 8
        versionName = "1.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API anahtarı APK'ya gömülmez; kullanıcı Ayarlar ekranından girer.
        // (APK'ya gömülen anahtar, APK açılarak herkes tarafından okunabilir.)

        // Sadece telefon işlemcileri: ONNX Runtime ve ML Kit'in emülatör (x86) kütüphaneleri
        // APK'yı gereksiz büyütüyordu. Neredeyse tüm Android telefonlar ARM.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // Depodaki sabit debug anahtarı: her CI derlemesi aynı imzayı taşır,
            // TV/telefonda "adb install -r" ile kaldırmadan güncellenebilir. (Gizli değildir.)
            storeFile = file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)

    // Permissions
    implementation(libs.accompanist.permissions)

    // Guava
    implementation(libs.guava)

    implementation(libs.kotlinx.coroutines.play.services)

    // Networking
    implementation(libs.okhttp)

    // Offline+ çeviri motoru (OPUS-MT modeli için)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
