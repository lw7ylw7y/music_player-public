package app.songplayer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.songplayer.core.log.DiagnosticsText
import app.songplayer.core.log.ErrorLog

/** Reads the four facts "Copy diagnostics" shares (R-21) from the system and the error log. Blocking: call it off the main thread. */
class Diagnostics(private val context: Context, private val errorLog: () -> ErrorLog) {
    fun text(): String {
        // Whatever goes wrong in opening or reading the log (a full disk, a database that cannot be opened), the copy still
        // works and says the codes are unavailable: diagnostics must not be the thing that crashes.
        val recent = try {
            errorLog().recent()
        } catch (e: Exception) {
            null
        }
        val info = packageInfo()
        return DiagnosticsText.build(
            appVersion = "${info?.versionName ?: "unknown"} (${info?.longVersionCode ?: 0L})",
            phone = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            recent = recent,
        )
    }

    fun versionName(): String = packageInfo()?.versionName ?: "unknown"

    private fun packageInfo() = try {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } catch (e: Exception) {
        null
    }
}
