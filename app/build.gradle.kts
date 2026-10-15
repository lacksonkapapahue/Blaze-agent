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
        versionCode = 93
        versionName = "0.27.1"
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
            keepDebugSymbols += listOf("**/libneedle.so", "**/libbusybox.so", "**/libproot.so", "**/libproot_loader.so", "**/libproot_loader32.so", "**/libtalloc.so", "**/libwgproxy.so", "**/libblazerouter.so")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric screenshot tests (ui/ShotsTest) need the app's resources (fonts) and only run with BLAZE_SHOTS=1.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("blaze.shots", System.getenv("BLAZE_SHOTS") ?: "0")
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            // The compose test rule needs ui-test-manifest, which only the debug build has.
            if (it.name.contains("Release")) it.exclude("**/ShotsTest*")
            // A failing test prints its real message and causes in the CI log (not just "SQLException at Foo.kt:143").
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showCauses = true
                showStackTraces = true
            }
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // QR scan for Blaze Desktop pairing
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")   // browser VPN proxy, private-tab profiles, location-mask scripts
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")   // v0.24.6: rounded icon buttons (browser, pointer menu, overlay)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")   // one mind: idle memory review (MindReviewWorker)
    // v0.26.3 one memory store: blaze.db (Desktop / CLI schema v1, FTS5) on a bundled modern SQLite, the same on every Android.
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")   // v0.24.7 Magic Hand: circle to grab, on-device OCR (bundled model, no network)
    implementation("com.alphacephei:vosk-android:0.3.47") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // the real org.json (android.jar only has stubs)
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")   // v0.26.3: blaze.db tests on a JVM SQLite with FTS5 (store/JdbcSql)
    // Screenshot tests: Compose UI test on Robolectric's native graphics.
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
