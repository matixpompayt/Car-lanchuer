plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Wspólny kod CAR OS (radio) i CAR OS Connect (telefon):
// protokół wymiany, szyfrowanie przekaźnika, wyszukiwanie stacji i postojów przy trasie.
// CAR OS ma go w repo (core/), Connect pobiera ten katalog przy budowaniu (gradle.properties: coreRef).
android {
    namespace = "pl.mati.caros.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
