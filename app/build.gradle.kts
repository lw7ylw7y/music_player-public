import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    // Room writes the schema of playlists.db here, so that a future migration can be tested against every version.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "app.songplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.songplayer"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The AcoustID application key (Technical Design 5.8) comes from `acoustid.key` in local.properties, which is not committed.
        // Unset means empty: the feature is then unavailable, and the build and the default tests do not need a key.
        val localProperties = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        val key = localProperties.getProperty("acoustid.key", "").trim().filter { it.isLetterOrDigit() }
        buildConfigField("String", "ACOUSTID_KEY", "\"$key\"")
    }

    // The upload key (Technical Design 14) lives outside the repository. Its four settings come from `local.properties` (not committed):
    // release.storeFile, release.storePassword, release.keyAlias, release.keyPassword. Without them the release bundle is built unsigned,
    // so a clean checkout still builds and the default tests need no key.
    val uploadKey = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val uploadKeyFile = uploadKey.getProperty("release.storeFile", "").trim()
    signingConfigs {
        if (uploadKeyFile.isNotEmpty()) {
            create("upload") {
                storeFile = file(uploadKeyFile)
                storePassword = uploadKey.getProperty("release.storePassword")
                keyAlias = uploadKey.getProperty("release.keyAlias")
                keyPassword = uploadKey.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // The emulator tests wait for animations to finish; with them off every screen test is faster.
        animationsDisabled = true
    }
}

// The L2 rule tests read the repository's sources and the merged release manifest.
tasks.withType<Test>().configureEach {
    systemProperty("repoRoot", rootDir.absolutePath)
    dependsOn(tasks.matching { it.name == "processReleaseMainManifest" })
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.androidx.glance.appwidget.testing)
    // Compose's test library pulls in an older Espresso that crashes on Android 16 and newer
    // (InputManager.getInstance was removed); 3.7.0 uses getSystemService instead.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
