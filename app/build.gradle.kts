plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.adepti.academy"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.adepti.academy"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    /* De bundel is al geoptimaliseerd door build-bundle.mjs; nog eens
       comprimeren levert vrijwel niets op en maakt het uitpakken trager. */
    androidResources {
        noCompress += listOf("woff2", "png", "jpg")
    }

    signingConfigs {
        create("release") {
            /* Vul deze in via gradle.properties of een omgevingsvariabele.
               Bewaar de keystore veilig: zonder exact dezelfde sleutel kan een
               latere APK niet over een bestaande installatie heen. */
            val storePathProp = providers.gradleProperty("ADEPTI_KEYSTORE").orNull
            if (storePathProp != null) {
                storeFile = file(storePathProp)
                storePassword = providers.gradleProperty("ADEPTI_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("ADEPTI_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("ADEPTI_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // de app is een dunne schil; niets om te snoeien
            if (providers.gradleProperty("ADEPTI_KEYSTORE").orNull != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // WebViewAssetLoader: serveert de bundel op een echte https-origin
    implementation("androidx.webkit:webkit:1.12.1")

    // Chrome Custom Tabs voor het forum en de externe tools
    implementation("androidx.browser:browser:1.8.0")
}
