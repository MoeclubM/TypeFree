import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.typefree.ime"
    compileSdk = 35

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }

    signingConfigs {
        create("release") {
            storeFile = file("typefree.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
                .ifEmpty { localProperties.getProperty("KEYSTORE_PASSWORD").orEmpty() }
                .ifEmpty { "0d000721" }
            keyAlias = System.getenv("KEY_ALIAS").orEmpty()
                .ifEmpty { localProperties.getProperty("KEY_ALIAS").orEmpty() }
                .ifEmpty { "typefree" }
            keyPassword = System.getenv("KEY_PASSWORD").orEmpty()
                .ifEmpty { localProperties.getProperty("KEY_PASSWORD").orEmpty() }
                .ifEmpty { "0d000721" }
        }
    }

    defaultConfig {
        applicationId = "com.typefree.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.security.crypto)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
