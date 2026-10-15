// v0.30.1: the optional YouTube Store plug-in (search, stream extraction, YouTube StoreSource). Pure Kotlin.
// It is only part of the build when this folder exists (settings.gradle.kts); the app finds it at runtime by class name.
// To ship Blaze without YouTube, delete or move this folder: nothing else needs to change.
plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    implementation(project(":storeapi"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    compileOnly("org.json:json:20240303")          // Android has org.json built in
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
