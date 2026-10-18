package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Reports whether Android's currently active/default network uses Wi-Fi transport.
 * Deliberately does not require NET_CAPABILITY_VALIDATED or INTERNET: captive-portal
 * router login pages often run on Wi-Fi networks which do not have validated Internet.
 */
object WifiConnectivity {
    fun isActiveWifiConnected(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = manager.activeNetwork
            if (activeNetwork != null) {
                val capabilities = manager.getNetworkCapabilities(activeNetwork)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return true
                }
            }
            // Fallback for captive portal Wi-Fi connected before validation as default route
            manager.allNetworks.any { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
