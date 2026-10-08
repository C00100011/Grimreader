import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.vdelaar.mylibby"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.vdelaar.mylibby"
        minSdk = 31
        targetSdk = 37
        // CI derives these from the git tag (see .github/workflows/build-apk.yml): -PappVersionName=1.2.3 -PappVersionCode=10203.
        // Local builds fall back to the values below.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 17
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.9.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // `-PabiOnly=arm64-v8a` builds a small APK for sideloading; the Play bundle is split per ABI by Play itself.
        (project.findProperty("abiOnly") as String?)?.let { abis -> ndk { abiFilters += abis.split(",") } }
    }

    // The upload key for Google Play lives outside the repository: ~/.config/grimreader/signing.properties
    // (storeFile, storePassword, keyAlias, keyPassword; see playstore/signing.properties.example).
    // Without that file the release build is signed with the debug key, so it still installs for testing.
    val signingProps = Properties().apply {
        val file = File(System.getProperty("user.home"), ".config/grimreader/signing.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val hasUploadKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !signingProps.getProperty(it).isNullOrBlank() }
    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                keepRules {
                    files.add(project.file("proguard-rules.pro"))
                }
            }
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.compose.material3.navigation.suite)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.security.crypto)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.onnxruntime.android)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
