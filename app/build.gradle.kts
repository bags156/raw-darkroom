plugins {
    id("com.android.application")
}

val appVersion: String = rootProject.file("version.txt").readText().trim()

/** "1.2.3" -> 10203, so a newer version always installs over an older one. */
fun versionCodeOf(version: String): Int {
    val parts = version.split(".").map { it.toInt() }
    require(parts.size == 3 && parts.all { it in 0..99 }) { "Version must look like 1.2.3, got $version" }
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "io.github.bags156.rawdarkroom"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bags156.rawdarkroom"
        minSdk = 30
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    // The signing key is deliberately public and committed (see signing/README.md), so every
    // build from any machine installs over any other. It proves nothing about who built an APK.
    signingConfigs {
        create("open") {
            storeFile = rootProject.file("signing/public-release.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "rawdarkroom"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("open")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("open")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // No runtime dependencies. Tests only.
    testImplementation("junit:junit:4.13.2")
}
