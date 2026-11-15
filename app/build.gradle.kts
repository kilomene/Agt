plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.novainc.agt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.novainc.agt"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.6.0"
        // Client config injected at build time from CI secrets (GitHub
        // repo secrets SUPABASE_ANON_KEY and TUNNEL_TOKEN). Local builds
        // without the env vars build with empty values and the legs
        // report not-configured, exactly like before.
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${System.getenv("SUPABASE_ANON_KEY") ?: ""}\"")
        buildConfigField("String", "TUNNEL_TOKEN", "\"${System.getenv("TUNNEL_TOKEN") ?: ""}\"")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Keep the rootfs asset uncompressed so the installer can copy it
        // by stream without depending on AssetFileDescriptor behaviour.
        noCompress += "bundle"
    }
    packaging {
        jniLibs {
            // The app EXECUTES its jniLibs (libproot.so, libcloudflared.so)
            // from the native library dir, so they must be extracted to
            // real files at install time. Without legacy packaging AGP
            // stores them uncompressed inside the APK and File.exists()
            // on nativeLibraryDir is false on the phone, which shipped
            // as "cloudflared binary missing from the app install".
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // HTTP for the Supabase and Firebase legs and rootfs download.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON for the store, jobs and Supabase payloads (works in unit tests too).
    implementation("com.google.code.gson:gson:2.10.1")
    // Tar extraction of the rootfs, snapshots and bundles (pure Java).
    implementation("org.apache.commons:commons-compress:1.26.1")
    // Firebase: real FCM push (FirebaseMessagingService) plus the
    // RTDB doorbell. The app is registered in project agentlink-84609;
    // google-services.json is materialized by CI from a repo secret.
    val firebaseBom = platform("com.google.firebase:firebase-bom:33.3.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-database")

    // Ed25519 device identity and signed jobs (pure Java, all API levels).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
