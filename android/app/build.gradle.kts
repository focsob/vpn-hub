plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI passes -PnodesUrl=https://raw.githubusercontent.com/<you>/<repo>/nodes/nodes.json
val nodesUrl = (project.findProperty("nodesUrl") as String?) ?: ""
val buildNumber = ((project.findProperty("buildNumber") as String?) ?: "1").toInt()
// owner/repo used to check GitHub Releases for app updates
val repoSlug = (project.findProperty("repoSlug") as String?) ?: ""

android {
    namespace = "com.vpnhub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vpnhub.app"
        minSdk = 36 // Android 16
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "NODES_URL", "\"$nodesUrl\"")
        buildConfigField("String", "REPO", "\"$repoSlug\"")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            // Fixed key so new builds install over old ones. Replace via secrets if you prefer.
            storeFile = file("vpnhub.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "vpnhub-keystore"
            keyAlias = System.getenv("KEY_ALIAS") ?: "vpnhub"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "vpnhub-keystore"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(fileTree("libs") { include("*.aar") }) // libbox.aar (sing-box core), built by CI

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
