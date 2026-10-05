plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.studybook.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.studybook.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0"

    }

    signingConfigs {
        // Personal sideloaded app: signed with this computer's debug key so updates install over earlier copies.
        // Keep ~/.android/debug.keystore backed up; a different key would mean uninstalling (and losing app data) first.
        create("personal") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // "Study Book": the app you use.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        // "Study Book (test)": installed alongside for automated checks, with its own data and book folder.
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Study Book (test)")
        }
    }

    androidResources {
        // The stroke data bundle is read in place, so it must not be compressed inside the APK.
        noCompress += "bin"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // One APK per CPU type, so each only carries one copy of the text recogniser's native library.
    // Most tablets need the arm64-v8a APK; x86_64 is for the emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Post-quantum crypto tables pulled in with PdfBox's Bouncy Castle dependency; never used here.
        resources.excludes += "org/bouncycastle/pqc/**"
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // The real org.json, as android.jar only has stubs in unit tests (for Backup).
    testImplementation("org.json:json:20240303")
}
