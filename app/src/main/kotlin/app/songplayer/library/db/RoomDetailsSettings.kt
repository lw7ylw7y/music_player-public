package app.songplayer.library.db

import app.songplayer.core.details.DetailsSettings
import app.songplayer.core.errors.StoreError

/** The song-details settings in the `setting` table (Technical Design 6). The feature is off until the user says yes (R-19). */
class RoomDetailsSettings(private val dao: SettingDao) : DetailsSettings {
    override fun enabled(): Boolean = read(ENABLED) == "1"

    /** "Also replace details that are already filled in" (R-32): off unless the user turned it on. */
    override fun replaceExisting(): Boolean = read(REPLACE) == "1"

    /** "Only new songs" (R-51): the time the user turned it on, or null when off. Songs added to the phone before it are never looked up. */
    override fun onlyNewSinceSec(): Long? = read(ONLY_NEW_SINCE)?.toLongOrNull()

    /** Turning it on records [nowSec] as the start of "new"; turning it off clears it, so turning it on again starts a new period. */
    fun setOnlyNew(on: Boolean, nowSec: Long) = put(ONLY_NEW_SINCE, if (on) nowSec.toString() else "")

    fun setReplaceExisting(on: Boolean) = put(REPLACE, if (on) "1" else "0")

    /** On unless the user turned it off (R-28). */
    override fun wifiOnly(): Boolean = read(WIFI_ONLY) != "0"

    /** The user's consent (R-19): turning the feature on records when; turning it off keeps the earlier time. */
    fun setEnabled(on: Boolean, nowMs: Long) {
        put(ENABLED, if (on) "1" else "0")
        if (on && consentAtMs() == null) put(CONSENT_AT, nowMs.toString())
    }

    /** When the user first agreed (R-19), or null if never: the consent dialog is shown only then. */
    fun consentAtMs(): Long? = read(CONSENT_AT)?.toLongOrNull()

    /** The number of songs waiting for review when the user last dismissed the Library banner. */
    fun bannerDismissedAt(): Int = read(BANNER_DISMISSED_AT)?.toIntOrNull() ?: 0

    fun setBannerDismissedAt(held: Int) = put(BANNER_DISMISSED_AT, held.toString())

    fun setWifiOnly(on: Boolean) = put(WIFI_ONLY, if (on) "1" else "0")

    override fun pausedUntilMs(): Long = read(PAUSED_UNTIL)?.toLongOrNull() ?: 0L

    override fun setPausedUntilMs(ms: Long) = put(PAUSED_UNTIL, ms.toString())

    private fun read(key: String): String? = try { dao.get(key) } catch (e: RuntimeException) { throw StoreError.ReadFailed() }

    private fun put(key: String, value: String) = try { dao.put(SettingEntity(key, value)) } catch (e: RuntimeException) { throw StoreError.WriteFailed() }

    private companion object {
        const val ENABLED = "detailsEnabled"
        const val PAUSED_UNTIL = "detailsPausedUntil"
        const val WIFI_ONLY = "detailsWifiOnly"
        const val REPLACE = "detailsReplace"
        const val ONLY_NEW_SINCE = "detailsOnlyNewSince"
        const val CONSENT_AT = "detailsConsentAt"
        const val BANNER_DISMISSED_AT = "detailsBannerDismissedAt"
    }
}
