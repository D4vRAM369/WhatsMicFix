plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.d4vram.whatsmicfix"
    // Si ya tienes Android 16 SDK instalado, puedes subir a 36 cuando esté estable.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.d4vram.whatsmicfix"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    // Xposed/LSPosed API (desde api.xposed.info, sin JitPack)
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")

    implementation("androidx.annotation:annotation:1.8.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
