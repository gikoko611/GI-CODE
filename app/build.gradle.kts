plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gikoko.gicode"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gikoko.gicode"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "0.0.1"
    }

    flavorDimensions += "device"
    productFlavors {
        create("redmi9a") {
            dimension = "device"
            applicationIdSuffix = ".redmi9a"
            versionNameSuffix = "-redmi9a"
            ndk { abiFilters += listOf("armeabi-v7a") }
            resValue("string", "app_name", "GIcode Redmi 9A")
        }
        create("universal") {
            dimension = "device"
            applicationIdSuffix = ".universal"
            versionNameSuffix = "-universal"
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
            resValue("string", "app_name", "GIcode Universal")
        }
    }

    buildTypes {
        getByName("release") {
            // CI fallback signing makes the first public APK installable.
            // Replace with a private persistent release keystore before publishing updates.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
