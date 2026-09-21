import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val personalSigning = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val isVerificationBuild = providers.gradleProperty("omniVerification").map { it == "true" }.getOrElse(false)
val cloudConfig = Properties().apply {
    val file = rootProject.file("cloud-backup.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val cloudEmulator = isVerificationBuild && providers.gradleProperty("omniCloudEmulator").getOrElse("false") == "true"

android {
    namespace = "com.gyftalala.omni"
    compileSdk = 34

    defaultConfig {
        applicationId = if (isVerificationBuild) "com.gyftalala.omni.verification" else "com.gyftalala.omni"
        manifestPlaceholders["omniAppName"] = if (isVerificationBuild) "Omni verification" else "Omni bot AI"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "0.1.20"
        buildConfigField("boolean", "CLOUD_EMULATOR", cloudEmulator.toString())
        mapOf("PROJECT_ID" to "projectId", "APP_ID" to "appId", "API_KEY" to "apiKey", "BUCKET" to "bucket", "WEB_CLIENT_ID" to "webClientId").forEach { (field, key) ->
            val value = if (cloudEmulator) when (key) {
                "projectId" -> "demo-omni-backup"
                "appId" -> "1:1234567890:android:0123456789abcdef"
                "apiKey" -> "fake-api-key"
                "webClientId" -> "1234567890-emulator.apps.googleusercontent.com"
                else -> "demo-omni-backup.appspot.com"
            } else if (isVerificationBuild) "" else cloudConfig.getProperty(key, "")
            require(value.matches(Regex("[A-Za-z0-9:._-]*"))) { "Invalid cloud client configuration: $key" }
            buildConfigField("String", "CLOUD_$field", "\"$value\"")
        }
        // Temporary testing preference requested by the owner. Set false to restore screenshot protection.
        buildConfigField("boolean", "ALLOW_TEST_SCREENSHOTS", providers.gradleProperty("omniAllowScreenshots").getOrElse("true"))

        testInstrumentationRunner = "com.gyftalala.omni.OmniTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    if (personalSigning.getProperty("storeFile") != null) {
        signingConfigs.create("personal") {
            storeFile = rootProject.file(personalSigning.getProperty("storeFile"))
            storePassword = personalSigning.getProperty("storePassword")
            keyAlias = personalSigning.getProperty("keyAlias")
            keyPassword = personalSigning.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("personal")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-storage")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
