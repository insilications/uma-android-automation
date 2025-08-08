plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.steve1316.uma_android_automation"
    //noinspection GradleDependency
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.steve1316.uma_android_automation"
        minSdk {
            version = release(24)
        }
        //noinspection ExpiredTargetSdkVersion
        targetSdk {
            version = release(30)
        }
        versionCode = 25
        versionName = "3.0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles.addAll(
                listOf(getDefaultProguardFile("proguard-android-optimize.txt"), file("proguard-rules.pro"))
            )

            applicationVariants.all {
                // Allow layout XMLs to get a reference to the application's version number.
                resValue("string", "versionName", "v${versionName}")

                // Auto-generate the file name.
                outputs.all {
                    val versionName = defaultConfig.versionName
                    val architecture = filters.first().identifier
                    outputFile.renameTo(file("v${versionName}-UmaAndroidAutomation-${architecture}.apk"))
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Specify which architecture to make apks for, or set universalApk to true for an all-in-one apk with increased file size.
    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include("armeabi-v7a", "arm64-v8a")
            // include "armeabi","armeabi-v7a",'arm64-v8a',"mips","x86","x86_64"
            isUniversalApk = false
        }
    }
}

dependencies {

    implementation ("androidx.core:core-ktx:1.16.0")
    implementation ("androidx.appcompat:appcompat:1.7.1")
    implementation ("com.google.android.material:material:1.12.0")
    implementation ("androidx.preference:preference-ktx:1.2.1")
    implementation ("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.9.2")
    implementation ("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation ("androidx.navigation:navigation-fragment-ktx:2.9.3")
    implementation ("androidx.navigation:navigation-ui-ktx:2.9.3")

    // OpenCV Android 4.12.0 for image processing.
    implementation(project(":opencv"))

    // Tesseract4Android for OCR text recognition.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // string-similarity to compare the string from OCR to the strings in data.
    implementation("net.ricecode:string-similarity:1.0.0")

    // Klaxon to parse JSON data files.
    implementation("com.beust:klaxon:5.6")

    // Google's Firebase Machine Learning OCR for Text Detection.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    // AppUpdater for notifying users when there is a new update available.
    implementation("com.github.javiersantos:AppUpdater:2.7")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}