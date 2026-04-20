plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fonephish.controller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fonephish.controller"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

val exportDebugApk = tasks.register<Copy>("exportDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/controller-debug.apk"))
    into(layout.buildDirectory.dir("exported-apk"))
    rename { "brawlcup.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(exportDebugApk)
}
