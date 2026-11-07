package app.songplayer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class ManifestRulesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun manifest() = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(RepoFiles.mergedReleaseManifest()).documentElement

    @Test
    fun Manifest_Internet_IsDeclaredForTheLookup() {
        val permissions = manifest().getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { (permissions.item(it) as Element).getAttributeNS(androidNs, "name") }
        assertTrue("INTERNET is declared for the song-details lookup (R-19): $names", names.any { it.endsWith(".INTERNET") })
        // Cleartext traffic stays off in the release build: no network security config opens it (the debug source set does, for localhost only).
        val application = manifest().getElementsByTagName("application").item(0) as Element
        assertFalse(application.hasAttributeNS(androidNs, "usesCleartextTraffic"))
        assertFalse(application.hasAttributeNS(androidNs, "networkSecurityConfig"))
    }

    @Test
    fun Manifest_WorkForegroundService_IsDataSync() {
        // The splitter's job calls setForeground with the data-sync type (V2); WorkManager's service must declare that type or Android 14 and newer refuse it.
        val services = manifest().getElementsByTagName("service")
        val work = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == "androidx.work.impl.foreground.SystemForegroundService" }
        assertTrue(work.getAttributeNS(androidNs, "foregroundServiceType").split("|").contains("dataSync"))
    }

    @Test
    fun Manifest_NetworkState_IsDeclared() {
        val permissions = manifest().getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { (permissions.item(it) as Element).getAttributeNS(androidNs, "name") }
        assertTrue("ACCESS_NETWORK_STATE is needed for the Wi-Fi-only constraint (R-28): $names", names.any { it.endsWith(".ACCESS_NETWORK_STATE") })
    }

    @Test
    fun Architecture_ExportedComponents_AreExactlyTheAllowedList() {
        // Grows with the stories: the playback service and the media-button receiver joined in B1.
        // A component that a library exports behind a permission (for example AndroidX's
        // ProfileInstallReceiver, which needs android.permission.DUMP, held only by the shell) is
        // not open to other apps; it is ignored here. Any other exported component fails the test.
        val allowed = setOf(
            "app.songplayer.MainActivity",
            "app.songplayer.playback.PlaybackService",
            "androidx.media3.session.MediaButtonReceiver",
            "app.songplayer.widget.SongWidgetReceiver",
        )
        val exported = mutableSetOf<String>()
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
            val nodes = manifest().getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                val open = e.getAttributeNS(androidNs, "exported") == "true" &&
                    e.getAttributeNS(androidNs, "permission").isEmpty()
                if (open) exported += e.getAttributeNS(androidNs, "name")
            }
        }
        assertEquals(allowed, exported)
    }

    @Test
    fun Manifest_ReleaseMerged_DeclaresExactlyTheAllowedPermissions() {
        // Technical Design 8: nothing else may be added without a decision.
        val allowed = setOf(
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC", // V2: the splitter's background job
            "android.permission.WAKE_LOCK",
            "android.permission.MANAGE_MEDIA", // V1.1, R-27: the user grants it in Settings
            "android.permission.INTERNET", // V1.1, R-19: the lookup only
            "android.permission.ACCESS_NETWORK_STATE", // V1.1, R-28: the Wi-Fi-only constraint of the background lookup
        )
        val nodes = manifest().getElementsByTagName("uses-permission")
        val declared = (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }.toSet()
        // A library may add a private "<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" for its own receivers; it is not ours to list.
        val others = declared.filterNot { it in allowed || it.endsWith("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
        assertEquals("permissions outside Technical Design 8: $others", emptyList<String>(), others)
        assertEquals("permissions missing: ${allowed - declared}", emptySet<String>(), allowed - declared)
    }

    @Test
    fun BackupRules_IncludeOnlyPlaylistsDb() {
        // R-17: the playlists go to Google's backup and to a new phone; the song cache, the queue and the resume state do not.
        val app = manifest().getElementsByTagName("application").item(0) as Element
        assertEquals("true", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
        assertEquals("fullBackupContent is not needed from API 33", "", app.getAttributeNS(androidNs, "fullBackupContent"))

        val rules = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(RepoFiles.root, "app/src/main/res/xml/data_extraction_rules.xml")).documentElement
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = rules.getElementsByTagName(section).item(0) as Element
            val includes = node.getElementsByTagName("include")
            val included = (0 until includes.length).map { (includes.item(it) as Element).let { e -> e.getAttribute("domain") to e.getAttribute("path") } }
            assertEquals("$section includes only the playlists", listOf("database" to "playlists.db"), included)
            // app.db is left out because it is not named: one include leaves out every other file (Lint refuses an exclude outside an include).
            assertTrue("$section does not name app.db", included.none { it.second == "app.db" })
        }
    }

    @Test
    fun Manifest_DeclaresAMediaApp_ForAndroidAuto() {
        // R-16: Android Auto lists only apps that declare this meta-data and a description that says "media".
        val metas = manifest().getElementsByTagName("meta-data")
        val car = (0 until metas.length).map { metas.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == "com.google.android.gms.car.application" }
        assertEquals("@xml/automotive_app_desc", car.getAttributeNS(androidNs, "resource"))
        val desc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(RepoFiles.root, "app/src/main/res/xml/automotive_app_desc.xml")).documentElement
        val uses = desc.getElementsByTagName("uses")
        assertEquals(listOf("media"), (0 until uses.length).map { (uses.item(it) as Element).getAttribute("name") })
    }

    @Test
    fun Manifest_Application_HasTheLauncherAndAttributionIcons_AndroidAutoAsksForBoth() {
        val app = manifest().getElementsByTagName("application").item(0) as Element
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(androidNs, "icon"))
        val metas = manifest().getElementsByTagName("meta-data")
        val attribution = (0 until metas.length).map { metas.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == "androidx.car.app.TintableAttributionIcon" }
        assertEquals("@drawable/ic_status_icon", attribution.getAttributeNS(androidNs, "resource"))
        for (file in listOf("mipmap-anydpi/ic_launcher.xml", "drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_background.xml", "drawable/ic_status_icon.xml")) {
            assertTrue("missing res/$file", File(RepoFiles.root, "app/src/main/res/$file").isFile)
        }
        val adaptive = File(RepoFiles.root, "app/src/main/res/mipmap-anydpi/ic_launcher.xml").readText()
        assertTrue("the themed (monochrome) layer is missing", adaptive.contains("<monochrome"))
    }

    @Test
    fun Manifest_MainActivity_AcceptsOnlyAViewOfAnMp3_AndIsSingleTask() {
        val activities = manifest().getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == "app.songplayer.MainActivity" }
        assertEquals("singleTask", main.getAttributeNS(androidNs, "launchMode"))
        val filters = main.getElementsByTagName("intent-filter")
        val viewFilters = (0 until filters.length).map { filters.item(it) as Element }.filter { filter ->
            val actions = filter.getElementsByTagName("action")
            (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(androidNs, "name") == "android.intent.action.VIEW" }
        }
        assertEquals("exactly one VIEW filter", 1, viewFilters.size)
        val data = viewFilters.single().getElementsByTagName("data")
        val mimeTypes = (0 until data.length).map { (data.item(it) as Element).getAttributeNS(androidNs, "mimeType") }
        assertEquals(listOf("audio/mpeg"), mimeTypes)
        for (i in 0 until data.length) {
            assertEquals("no scheme is named, and the code takes content addresses only", "", (data.item(i) as Element).getAttributeNS(androidNs, "scheme"))
        }
    }
}
