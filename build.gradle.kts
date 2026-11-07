plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// One task for everything: JVM tests, Android Lint, then the emulator tests (an emulator or phone must be running).
// Run with --continue to see every failure instead of stopping at the first.
tasks.register("checkAll") {
    group = "verification"
    description = "Runs :core:test, :app:testDebugUnitTest, :app:lintDebug and :app:connectedDebugAndroidTest."
    dependsOn(":core:test", ":app:testDebugUnitTest", ":app:lintDebug", ":app:connectedDebugAndroidTest")
}
