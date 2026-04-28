buildscript {
    val kotlinVersion = "2.2.0"
    val refineVersion = "4.4.0"
    val localProperties = java.util.Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }

    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/open-obfuscator/dProtect")
            credentials {
                username = localProperties.getProperty("gpr.user").orEmpty()
                password = localProperties.getProperty("gpr.token").orEmpty()
            }
        }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        classpath("dev.rikka.tools.refine:gradle-plugin:$refineVersion")
    }
}

extra["compileSdkVersion"] = 36
extra["compileSdkMinorVersion"] = 1
extra["buildToolsVersion"] = "36.0.0"
extra["minSdkVersion"] = 24
extra["targetSdkVersion"] = 36
extra["kotlinVersion"] = "2.2.0"
extra["refineVersion"] = "4.4.0"
extra["shizukuVersion"] = "13.1.5"
extra["ktorVersion"] = "2.3.5"
extra["appcompatVersion"] = "1.6.1"
extra["coroutineVersion"] = "1.7.3"
extra["ktxVersion"] = "1.12.0"

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
