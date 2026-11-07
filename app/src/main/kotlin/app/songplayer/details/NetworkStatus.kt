package app.songplayer.details

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What the Song details screen needs to say "Waiting for Wi-Fi" or "No internet right now" (needs ACCESS_NETWORK_STATE). */
class NetworkStatus(context: Context) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** True when the phone has a network that reaches the internet. */
    fun connected(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    /** True when that network is not metered (Wi-Fi, usually): the same test WorkManager's UNMETERED constraint uses. */
    fun unmetered(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun capabilities(): NetworkCapabilities? = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
}
