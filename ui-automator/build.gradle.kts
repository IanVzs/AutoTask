plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val compileSdkApi = rootProject.extra["compileSdkVersion"] as Int
val compileSdkMinorVersion = rootProject.extra["compileSdkMinorVersion"] as Int
val buildTools = rootProject.extra["buildToolsVersion"] as String
val minSdkApi = rootProject.extra["minSdkVersion"] as Int

android {
    namespace = "androidx.test.uiautomator"
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
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
