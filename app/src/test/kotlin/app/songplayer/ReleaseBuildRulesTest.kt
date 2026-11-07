package app.songplayer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release build is shrunk, never debuggable, and no signing secret is in the repository (Technical Design 14). */
class ReleaseBuildRulesTest {
    private val build = File(RepoFiles.root, "app/build.gradle.kts").readText()

    @Test
    fun Release_IsShrunk_AndNotDebuggable() {
        val release = build.substringAfter("release {").substringBefore("\n        }")
        assertTrue("release must turn R8 on", Regex("""isMinifyEnabled\s*=\s*true""").containsMatchIn(release))
        assertTrue("release must shrink resources", Regex("""isShrinkResources\s*=\s*true""").containsMatchIn(release))
        assertTrue("release must never be debuggable", !Regex("""isDebuggable\s*=\s*true""").containsMatchIn(release))
        assertTrue("the rules file must exist", File(RepoFiles.root, "app/proguard-rules.pro").isFile)
    }

    @Test
    fun Release_NoKeyOrPasswordIsInTheRepository() {
        assertTrue("passwords must come from local.properties, never be written in the build file",
            !Regex("""(storePassword|keyPassword)\s*=\s*"[^"]""").containsMatchIn(build))
        val keys = RepoFiles.root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }
            .filter { it.isFile && it.extension in setOf("jks", "keystore", "p12", "pfx") }.toList()
        assertTrue("a key file is in the repository: ${keys.map { it.name }}", keys.isEmpty())
        assertTrue("local.properties must be ignored", File(RepoFiles.root, ".gitignore").readText().lines().any { it.trim() == "local.properties" })
    }
}
