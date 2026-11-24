import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val suppliedSupabaseUrl = providers.gradleProperty("SUPABASE_URL").orElse(providers.environmentVariable("SUPABASE_URL")).orElse(localProps.getProperty("SUPABASE_URL", "")).get()
val suppliedSupabaseKey = providers.gradleProperty("SUPABASE_PUBLISHABLE_KEY").orElse(providers.environmentVariable("SUPABASE_PUBLISHABLE_KEY")).orElse(localProps.getProperty("SUPABASE_PUBLISHABLE_KEY", "")).get()
// Supabase publishable client settings are public by design; these defaults make app-release work
// even when CI doesn't pass optional overrides.
val supabaseUrl = suppliedSupabaseUrl.ifBlank { "https://afbcxhlelghlqzwmjsvz.supabase.co" }
val supabaseKey = suppliedSupabaseKey.ifBlank { "sb_publishable_5Z20E0_YCZoo3sN88LEABQ_FuCwe0mG" }

android {
    namespace = "com.jamusic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jamusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabaseKey\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug { }
        release {
            // A sandbox/dev-signed RELEASE APK (not the debug variant). Replace with a private
            // signing config before publishing to Play Store.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
