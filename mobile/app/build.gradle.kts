plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thesis.middleware"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thesis.middleware"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
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

    testOptions {
        unitTests {
            // android.util.Log is a stub that throws "not mocked" by default;
            // ExecutionProxy logs on every path, so tests need it to no-op.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Local database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // WorkManager (background tasks / retry)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // NOTE: tensorflow-lite was removed — the RF model is a plain JSON forest
    // walked by RandomForestModel.kt (see PHASE2_4_ON_DEVICE_DEPLOYMENT.md,
    // "Why a manual port"). The dependency was never imported anywhere and cost
    // ~3 MB of APK.

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    // Real org.json on the JVM. The android.jar used for unit tests ships stubs
    // that throw "not mocked", so RandomForestModel.fromJson would be untestable
    // without this.
    testImplementation("org.json:json:20240303")
}
