import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("dev.rikka.tools.refine")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val compileSdkApi = rootProject.extra["compileSdkVersion"] as Int
val compileSdkMinorVersion = rootProject.extra["compileSdkMinorVersion"] as Int
val buildTools = rootProject.extra["buildToolsVersion"] as String
val minSdkApi = rootProject.extra["minSdkVersion"] as Int
val targetSdkApi = rootProject.extra["targetSdkVersion"] as Int
val kotlinVersion = rootProject.extra["kotlinVersion"] as String
val shizukuVersion = rootProject.extra["shizukuVersion"] as String
val ktorVersion = rootProject.extra["ktorVersion"] as String
val appcompatVersion = rootProject.extra["appcompatVersion"] as String
val coroutineVersion = rootProject.extra["coroutineVersion"] as String
val ktxVersion = rootProject.extra["ktxVersion"] as String
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val hasXjunzSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !localProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "top.xjunz.tasker"
    buildToolsVersion = buildTools
    compileSdk {
        version = release(compileSdkApi) {
            minorApiLevel = compileSdkMinorVersion
        }
    }

    signingConfigs {
        if (hasXjunzSigning) {
            create("xjunz") {
                storeFile = file(localProperties.getProperty("storeFile"))
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "top.xjunz.tasker"
        minSdk = minSdkApi
        targetSdk = targetSdkApi
        versionCode = providers.gradleProperty("APP_VERSION_CODE").get().toInt()
        versionName = buildString {
            append(providers.gradleProperty("APP_VERSION_NAME").get())
            if (gradle.startParameter.taskNames.any { it.lowercase().contains("debug") }) {
                append("-debug")
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("x86", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasXjunzSigning) {
                signingConfig = signingConfigs.getByName("xjunz")
            }
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            if (hasXjunzSigning) {
                signingConfig = signingConfigs.getByName("xjunz")
            }
        }
    }

    buildFeatures {
        dataBinding = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_18
        targetCompatibility = JavaVersion.VERSION_18
    }

    kotlinOptions {
        jvmTarget = "18"
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    compileOnly(project(":hidden-apis"))
    implementation(project(":tasker-engine"))
    implementation(project(":coroutine-ui-automator"))
    implementation(project(":shared-library"))
    implementation(project(":ssl"))

    val appCenterSdkVersion = "5.0.2"
    implementation("com.microsoft.appcenter:appcenter-analytics:$appCenterSdkVersion")
    implementation("com.microsoft.appcenter:appcenter-crashes:$appCenterSdkVersion")

    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    implementation("androidx.core:core-ktx:$ktxVersion")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("androidx.appcompat:appcompat:$appcompatVersion")
    implementation("com.google.android.material:material:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutineVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutineVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")

    val lifecycleVersion = "2.6.2"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("me.zhanghai.android.appiconloader:appiconloader:1.5.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
