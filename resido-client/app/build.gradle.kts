import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the release script (build.sh) via -P properties so a
// release bump is a one-line .env edit, mirroring the desktop clients'
// RESIDO_CLIENT_VERSION flow. Defaults keep plain IDE/debug builds working.
val residoVersionName = (project.findProperty("residoVersionName") as String?) ?: "1.0.0"
val residoVersionCode = (project.findProperty("residoVersionCode") as String?)?.toInt() ?: 10000

// App label comes from the Laravel project root .env (APP_NAME), passed by
// build.sh as a -P property, same lookup the desktop generator scripts do.
val residoAppName = (project.findProperty("residoAppName") as String?) ?: "Resido"

// Update host override for debug/testing builds; release default matches the
// production update vhost (analogous to residowindows.vorntech.sk).
val residoUpdateUrl = (project.findProperty("residoUpdateUrl") as String?)
    ?: "https://residoandroid.vorntech.sk/"

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        load(FileInputStream(file))
    }
}

android {
    namespace = "sk.efabrica.resido"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.efabrica.resido"
        minSdk = 26
        targetSdk = 35
        versionCode = residoVersionCode
        versionName = residoVersionName

        manifestPlaceholders["appName"] = residoAppName
        buildConfigField("String", "UPDATE_BASE_URL", "\"$residoUpdateUrl\"")
    }

    // Two distribution channels with different update stories:
    //  - sideload: APK on the own update host, in-app self-update (UpdateManager)
    //  - play: Google Play - the store handles updates, and Play policy forbids
    //    self-updating apps, so the updater and REQUEST_INSTALL_PACKAGES are out
    // A device can only follow one channel - the signing keys differ (Play App
    // Signing vs our keystore), so switching requires a reinstall.
    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No shrinking: the app is a thin WebView shell, and R8 config
            // maintenance is not worth the few hundred KB on a sideload APK.
            isMinifyEnabled = false
            // Null when keystore.properties is missing → unsigned release APK.
            // build.sh generates the keystore before invoking assembleRelease,
            // and erroring here would break plain debug builds too.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")
}
