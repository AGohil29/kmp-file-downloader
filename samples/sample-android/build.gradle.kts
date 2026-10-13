plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.arun.downloader.sample.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arun.downloader.sample.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":downloader-core"))
    implementation(project(":downloader-filesystem"))
    implementation(project(":downloader-storage"))
    implementation(project(":downloader-network"))
    implementation(project(":downloader-runtime"))

    implementation(libs.sqldelight.driver.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    // Okio dependency for path manipulation
    implementation(libs.okio)

    // Ktor engine used to build the HttpClient on Android
    implementation(libs.ktor.client.okhttp)

    // Jetpack Compose BoM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.activity.compose)
}

