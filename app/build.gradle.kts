plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.d4vram.whatsmicfix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.d4vram.whatsmicfix"
        minSdk = 24
        targetSdk = 35
        versionCode = 120
        versionName = "1.2"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        getByName("debug") {
            isMinifyEnabled = false

            // --- Comentado para permitir actualización de la v1.0 ---
            // applicationIdSuffix = ".v11"   // com.d4vram.whatsmicfix.v11
            // versionNameSuffix = "-v1.1"    // Comentado para mostrar solo versión 1.2
            // Si tu Manifest usa @string/app_name, puedes diferenciar el nombre visible:
            // resValue("string", "app_name", "WhatsMicFix v1.1")
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
    // Xposed/LSPosed API
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")

    implementation("androidx.annotation:annotation:1.8.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
