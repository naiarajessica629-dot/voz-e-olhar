plugins {
    id("com.android.application")
}

android {
    namespace = "br.com.vozeolhar"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.vozeolhar"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
