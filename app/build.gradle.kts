plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voxelgame"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voxelgame.blocos"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
    }

    signingConfigs {
        create("dbg") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // release = nao-debuggable (mais rapido), assinado com a chave de debug para instalar direto
            signingConfig = signingConfigs.getByName("dbg")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
            pickFirsts += setOf("META-INF/MANIFEST.MF")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // scripts dos mods: Lua (principal) e JavaScript (suporte menor, modo interpretado)
    implementation("org.luaj:luaj-jse:3.0.1")
    implementation("org.mozilla:rhino:1.7.14")
}
