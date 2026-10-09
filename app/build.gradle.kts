plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.studybook.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wonger.hok6"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.12"

    }

    signingConfigs {
        // Google Play upload key. Its location and passwords live outside the repo in ~/.gradle/gradle.properties
        // (hok6.upload.storeFile, .storePassword, .keyAlias, .keyPassword). Back up the keystore: a lost upload key
        // has to be reset through Play Console support.
        create("upload") {
            val props = project.properties
            storeFile = (props["hok6.upload.storeFile"] as String?)?.let { file(it) }
            storePassword = props["hok6.upload.storePassword"] as String?
            keyAlias = props["hok6.upload.keyAlias"] as String?
            keyPassword = props["hok6.upload.keyPassword"] as String?
        }
    }

    buildTypes {
        // "Hok6": the app you use.
        release {
            // Shrinking drops the unused parts of PdfBox, Bouncy Castle and the other libraries: a smaller APK that starts faster.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the upload key (someone building from source), fall back to the debug key so the APK still installs.
            val upload = signingConfigs.getByName("upload")
            signingConfig = if (upload.storeFile?.exists() == true) upload else signingConfigs.getByName("debug")
        }
        // "Hok6 (test)": installed alongside for automated checks, with its own data and book folder.
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Hok6 (test)")
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
    // Off for the Google Play bundle (bundleRelease): Play splits by CPU type itself, and AGP refuses both at once.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
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
    implementation("androidx.input:input-motionprediction:1.0.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // The real org.json, as android.jar only has stubs in unit tests (for Backup).
    testImplementation("org.json:json:20240303")
}
