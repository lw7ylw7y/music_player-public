package app.songplayer.library

import app.songplayer.core.text.SortOrder
import app.songplayer.library.db.SettingDao
import app.songplayer.library.db.SettingEntity

enum class LibraryTab { Songs, Albums, Artists, Playlists }

/**
 * The Library's remembered view (R-25) and whether the music-access question was asked before, all in the
 * `setting` table (Technical Design 5.8). Blocking calls: run them off the main thread. A missing or
 * unreadable value means the default.
 */
class LibraryPrefs(private val settings: SettingDao) {
    fun tab(): LibraryTab = settings.get(TAB)?.let { name -> LibraryTab.entries.firstOrNull { it.name == name } } ?: LibraryTab.Songs

    fun sort(): SortOrder = settings.get(SORT)?.let { name -> SortOrder.entries.firstOrNull { it.name == name } } ?: SortOrder.Title

    /** True when the user reversed the order of the sort; anything else stored, or nothing, is the default direction. */
    fun sortReversed(): Boolean = settings.get(SORT_REVERSED) == "1"

    fun saveTab(tab: LibraryTab) = settings.put(SettingEntity(TAB, tab.name))

    fun saveSort(sort: SortOrder) = settings.put(SettingEntity(SORT, sort.name))

    fun saveSortReversed(reversed: Boolean) = settings.put(SettingEntity(SORT_REVERSED, if (reversed) "1" else "0"))

    /** True once the Android permission dialog has been shown, so a later "not allowed" is the refused screen. */
    fun accessAsked(): Boolean = settings.get(ACCESS_ASKED) == "1"

    fun saveAccessAsked() = settings.put(SettingEntity(ACCESS_ASKED, "1"))

    /** True once the notification question was shown (UX D-13: asked the first time a song starts, never again). */
    fun notificationAsked(): Boolean = settings.get(NOTIFICATION_ASKED) == "1"

    fun saveNotificationAsked() = settings.put(SettingEntity(NOTIFICATION_ASKED, "1"))

    private companion object {
        const val NOTIFICATION_ASKED = "notificationAsked"
        const val TAB = "tab"
        const val SORT = "sort"
        const val SORT_REVERSED = "sortReversed"
        const val ACCESS_ASKED = "accessAsked"
    }
}
