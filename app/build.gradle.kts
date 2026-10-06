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
        versionCode = 4
        versionName = "1.3"
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
    // Alinha as versões internas do Kotlin trazidas pelas bibliotecas (evita "Duplicate class")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    // Leitura de texto e reconhecimento de objetos no próprio celular (grátis, sem chave)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
}
