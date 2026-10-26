plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing info comes from GitHub secrets (environment variables) during the cloud build.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.example.bgrecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bgrecorder"
        minSdk = 29
        targetSdk = 34
        // The cloud build passes the build number, so every release is newer than the last.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
