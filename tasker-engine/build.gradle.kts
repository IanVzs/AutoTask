plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val compileSdkApi = rootProject.extra["compileSdkVersion"] as Int
val compileSdkMinorVersion = rootProject.extra["compileSdkMinorVersion"] as Int
val buildTools = rootProject.extra["buildToolsVersion"] as String
val minSdkApi = rootProject.extra["minSdkVersion"] as Int
val ktorVersion = rootProject.extra["ktorVersion"] as String
val appcompatVersion = rootProject.extra["appcompatVersion"] as String

android {
    namespace = "top.xjunz.tasker.engine"
    buildToolsVersion = buildTools
    compileSdk {
        version = release(compileSdkApi) {
            minorApiLevel = compileSdkMinorVersion
        }
    }

    defaultConfig {
        minSdk = minSdkApi
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(project(":ui-automator"))
    implementation(project(":shared-library"))
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("androidx.appcompat:appcompat:$appcompatVersion")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
