import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versione dell'app: incrementata da "Pubblica release.command".
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.easycrypt.keyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.easycrypt.keyboard"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.getProperty("VERSION_CODE").toInt()
        versionName = appVersion.getProperty("VERSION_NAME")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmata con la chiave di debug di questo Mac (~/.android/debug.keystore), così l'APK è installabile.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    // Usato per verificare con la web app un testo cifrato prodotto da Kotlin.
    System.getProperty("easycrypt.out")?.let { systemProperty("easycrypt.out", it) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
