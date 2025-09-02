import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

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
        versionCode = 130
        versionName = "1.3"
    }

    signingConfigs {
        create("release") {
            keystoreProps["storeFile"]?.let { storeFile = file(it as String) }
            storePassword = keystoreProps["storePassword"] as String?
            keyAlias = keystoreProps["keyAlias"] as String?
            keyPassword = keystoreProps["keyPassword"] as String?
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
            // Comentado para permitir actualización sobre la 1.0
            // applicationIdSuffix = ".v11"
            // versionNameSuffix = "-v1.1"
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
