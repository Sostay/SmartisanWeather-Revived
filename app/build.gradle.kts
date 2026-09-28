plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.compose.compiler)
}

// Release signing is injected by CI from repository secrets, see
// .github/workflows/android-release.yml. Each value may equally come from a
// local ~/.gradle/gradle.properties entry or from a -PRELEASE_* command-line
// property. When any of the four is missing the release variant is simply left
// unsigned, so a fresh clone can still run tests, lint and assembleDebug.
val releaseStoreFile: String? = providers.gradleProperty("RELEASE_STORE_FILE").orNull
    ?: providers.environmentVariable("RELEASE_STORE_FILE").orNull
val releaseStorePassword: String? = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
    ?: providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
    ?: providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
    ?: providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.smartisan.weather"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.smartisanweather.revived"
        minSdk = 27
        targetSdk = 37
        versionCode = 6
        versionName = "0.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        val devKeystore = file("${rootDir}/signing/debug.keystore")
        if (devKeystore.exists()) {
            getByName("debug") {
                storeFile = devKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        } else if (devKeystore.exists()) {
            create("release") {
                storeFile = devKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // Falls back to the default debug key when the RELEASE_* values are absent,
            // ensuring the release APK is still signed and installable on test devices.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                keepRules {
                    // The app has no XML onClick, JNI, JS bridge, reflected enum, or
                    // custom string-based View property. Required Parcelable entry
                    // points are retained explicitly in proguard-rules.pro.
                    includeDefault = false
                }
            }
            proguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { versionName ->
                    "SmartisanWeather-Revived-$versionName.apk"
                }
            )
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room3.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // API 37 removed the private InputManager singleton used by older Espresso transitives.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.withType<Test> {
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
