package com.arbakker.blueshift

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

object NetworkDetector {
    
    // DEBUG: Set to empty string to use real WiFi, or set to a network name to simulate
    private const val DEBUG_OVERRIDE_SSID: String = ""  // Change to "Home", "Office", "VacationHome" to test
    
    /**
     * Get current WiFi SSID
     * Returns SSID like "MyHomeWiFi" or null if not connected to WiFi
     * 
     * Note: On Android 10+ (API 29), requires ACCESS_FINE_LOCATION permission
     */
    fun getCurrentWiFiSSID(context: Context): String? {
        // DEBUG: Return override SSID if set
        if (DEBUG_OVERRIDE_SSID.isNotEmpty()) {
            return DEBUG_OVERRIDE_SSID
        }
        
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Check if connected to WiFi
            val network = connectivityManager.activeNetwork ?: return null
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            
            if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null // Not on WiFi
            }
            
            // Get SSID
            // WifiManager.connectionInfo is deprecated but the modern NetworkCallback API
            // requires API 31+ and is more complex. Since minSdk is 34, this still works fine.
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo.ssid
            
            // Handle special cases
            return when {
                ssid == null -> null
                ssid == "<unknown ssid>" -> null // Hidden SSID or no permission
                ssid == "0x" -> null // Not connected
                ssid.startsWith("\"") && ssid.endsWith("\"") -> ssid.substring(1, ssid.length - 1)
                else -> ssid
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Check if location permission is granted (required for SSID on Android 10+)
     */
    fun hasLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true // Not needed on older versions
        }
        
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == 
            PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Generate user-friendly network name suggestion based on SSID
     */
    fun suggestNetworkName(ssid: String?): String {
        return when {
            ssid.isNullOrBlank() -> "Network"
            ssid.contains("home", ignoreCase = true) -> "Home"
            ssid.contains("office", ignoreCase = true) -> "Office"
            ssid.length > 20 -> ssid.take(20) + "..."
            else -> ssid
        }
    }
}
