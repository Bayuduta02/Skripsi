import org.jetbrains.kotlin.storage.CacheResetOnProcessCanceled.enabled

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.skripsi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.skripsi"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.tensorflow.lite) // Core TensorFlow Lite runtime library
    implementation(libs.tensorflow.lite.gpu) // Optional, for GPU acceleration
    implementation(libs.tensorflow.lite.support) // Optional, for pre/post-processing data
    implementation(libs.tensorflow.lite.task.vision) // Optional, for vision-related tasks
    implementation(libs.tensorflow.lite.select.tf.ops) // Optional, to allow selective TensorFlow operators.
    implementation (libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation (libs.androidx.viewpager2)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.lifecycle)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}