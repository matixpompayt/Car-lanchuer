plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ten sam numer builda co CAR OS w radiu (github.run_number).
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
val carosVersion = (project.findProperty("carosVersion") as String?) ?: "1.0"

android {
    namespace = "pl.mati.carosparking"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.mati.carosparking"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "$carosVersion.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"matixpompayt/Car-lanchuer\"")
    }

    signingConfigs {
        create("caros") {
            storeFile = rootProject.file("keystore/caros.jks")
            storePassword = "caros-2026"
            keyAlias = "caros"
            keyPassword = "caros-2026"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("caros") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("caros")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
