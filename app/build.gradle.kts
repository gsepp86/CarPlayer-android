plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.carplayer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carplayer.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 19
        versionName = "2.8"
    }

    signingConfigs {
        create("release") {
            storeFile = file("carplayer.jks")
            storePassword = "carplayer"
            keyAlias = "carplayer"
            keyPassword = "carplayer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // ExoPlayer (Media3): decodifica e uscita audio su thread propri.
    // Sostituisce il vecchio motore PCM scritto a mano, che bloccava l'interfaccia.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
}
