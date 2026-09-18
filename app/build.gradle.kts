import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

/**
 * Release signing comes from SIGNING_* environment variables when set (CI passes the
 * repo secrets this way — a .properties file would mangle a password containing a
 * backslash, since Java treats it as an escape), otherwise from `keystore.properties`
 * at the repo root for local builds. Both are gitignored along with the keystore —
 * neither the key nor its passwords belong in version control. When neither source is
 * present (a fresh clone, CI without the secrets) the release build still succeeds
 * and simply produces an unsigned APK rather than failing on a missing property.
 *
 * See "Signed release build" in CLAUDE.md.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}
fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotEmpty() } ?: keystoreProperties.getProperty(propertyName)
val signingStoreFile = signingValue("SIGNING_STORE_FILE", "storeFile")
val hasSigningConfig = signingStoreFile != null

android {
    namespace = "org.castberg.markdowncapture"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.castberg.markdowncapture"
        minSdk = 26
        targetSdk = 36
        // Bump versionCode for every build installed over an existing one; Android
        // refuses a downgrade. versionName is what shows in Settings > Apps.
        versionCode = 3
        versionName = "1.2"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        create("release") {
            if (hasSigningConfig) {
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasSigningConfig) signingConfigs.getByName("release") else null
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX
    implementation("androidx.camera:camera-core:1.6.0")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OkHttp for LLM API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // DocumentFile for Storage Access Framework
    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
