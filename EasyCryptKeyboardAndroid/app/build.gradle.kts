import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versione dell'app: incrementata da "Pubblica release.command".
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val signingProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        (System.getenv("EASYCRYPT_KEYSTORE") ?: signingProperties.getProperty("storeFile"))?.let { path ->
            create("distribution") {
                storeFile = file(path)
                storePassword = System.getenv("EASYCRYPT_KEYSTORE_PASSWORD") ?: signingProperties.getProperty("storePassword")
                keyAlias = System.getenv("EASYCRYPT_KEY_ALIAS") ?: signingProperties.getProperty("keyAlias")
                keyPassword = System.getenv("EASYCRYPT_KEY_PASSWORD") ?: signingProperties.getProperty("keyPassword") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Dalla 1.0.11 si usa la chiave dedicata, conservata fuori dal repository.
            signingConfig = signingConfigs.findByName("distribution") ?: signingConfigs.getByName("debug")
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
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Impedisce di pubblicare per errore APK che richiederebbero disinstallazione e perdita dei dati.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        val config = android.buildTypes.getByName("release").signingConfig!!
        val store = KeyStore.getInstance(config.storeType ?: KeyStore.getDefaultType())
        config.storeFile!!.inputStream().use { store.load(it, config.storePassword!!.toCharArray()) }
        val certificate = requireNotNull(store.getCertificate(config.keyAlias)) { "Certificato di firma non trovato" }
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        check(digest == "1130f6ade00101e6bcf9e2bf383a03b61a76fe3afeca2a542f4ef0e7fdc5ef77") {
            "Firma Android errata. Configura keystore.properties o EASYCRYPT_KEYSTORE con la chiave EasyCrypt della versione 1.0.11."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }
