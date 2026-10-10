plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.blaze.assistant"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.blaze.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "1.11.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    // One permanent key kept with the project, so every new APK installs over the old one
    // and keeps your keys, settings, chats and voice model.
    signingConfigs {
        create("blaze") {
            storeFile = rootProject.file("keystore/blaze.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("blaze") }
        // Release = what you install: R8-optimised, not debuggable (Compose runs far smoother).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("blaze")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // The Needle runner ships as an executable named lib*.so so Android unpacks it into nativeLibraryDir.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libneedle.so", "**/libbusybox.so", "**/libproot.so", "**/libproot_loader.so", "**/libproot_loader32.so", "**/libtalloc.so")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("com.alphacephei:vosk-android:0.3.47") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
