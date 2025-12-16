package com.arbakker.blueshift

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.UUID

data class DiscoveredPlayer(
    val host: String,
    val port: Int,
    val name: String,
    val model: String?
)

class PlayerDiscovery {
    companion object {
        private const val TAG = "PlayerDiscovery"
        private const val BLUOS_PORT = 11000
        private const val TIMEOUT_MS = 2000L
        private const val STATUS_ENDPOINT = "/Status"
        
        /**
         * Discover BluOS players on the local network by scanning common IP ranges
         */
        suspend fun discoverPlayers(context: Context): List<DiscoveredPlayer> = withContext(Dispatchers.IO) {
            
            val networkInfo = getNetworkInfo(context)
            if (networkInfo == null) {
                Log.e(TAG, "Could not determine network information")
                return@withContext emptyList()
            }
            
            val (subnet, prefixLength) = networkInfo
            
            // Calculate IP range based on subnet mask
            val ipRange = calculateIpRange(subnet, prefixLength)
            
            // Scan the IP range
            val discoveredPlayers = mutableListOf<DiscoveredPlayer>()
            val jobs = ipRange.map { host ->
                async {
                    checkHost(host, BLUOS_PORT)
                }
            }
            
            val results = jobs.awaitAll()
            discoveredPlayers.addAll(results.filterNotNull())
            
            return@withContext discoveredPlayers
        }
        
        /**
         * Get the local network info (subnet and prefix length)
         * Returns pair of (subnet base IP, prefix length) e.g., ("192.168.1.0", 24)
         */
        private fun getNetworkInfo(context: Context): Pair<String, Int>? {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ipAddress = wifiInfo.ipAddress
                
                if (ipAddress == 0) {
                    Log.w(TAG, "No WiFi connection")
                    return null
                }
                
                
                // Convert IP address from int to string
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    (ipAddress shr 8) and 0xff,
                    (ipAddress shr 16) and 0xff,
                    (ipAddress shr 24) and 0xff
                )
                
                
                // Get DHCP info which includes subnet mask
                val dhcpInfo = wifiManager.dhcpInfo
                val netmaskInt = dhcpInfo.netmask
                
                // Convert netmask to prefix length (e.g., 255.255.255.0 = /24)
                var prefixLength = Integer.bitCount(netmaskInt)
                
                // Fallback: if netmask is 0 or invalid, assume /24 (most common home network)
                if (prefixLength == 0 || netmaskInt == 0) {
                    Log.w(TAG, "Invalid netmask from DHCP, defaulting to /24")
                    prefixLength = 24
                }
                
                
                // Calculate network address
                val networkInt = if (netmaskInt != 0) {
                    // Use actual netmask if available
                    val result = ipAddress and netmaskInt
                    result
                } else {
                    // For /24, manually calculate: keep first 3 octets, set last to 0
                    // In little-endian: 0x00FFFFFF keeps first 3 bytes (192.168.1.x -> 192.168.1.0)
                    val result = ipAddress and 0x00FFFFFF
                    result
                }
                
                val networkAddress = intToIp(networkInt)
                
                
                return Pair(networkAddress, prefixLength)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting network info", e)
            }
            
            return null
        }
        
        /**
         * Calculate all usable IP addresses in a subnet
         */
        private fun calculateIpRange(networkAddress: String, prefixLength: Int): List<String> {
            val parts = networkAddress.split(".").map { it.toInt() }
            // Use little-endian format like Android does
            val networkInt = parts[0] or (parts[1] shl 8) or (parts[2] shl 16) or (parts[3] shl 24)
            
            // Calculate number of hosts (2^(32-prefixLength) - 2, excluding network and broadcast)
            val hostBits = 32 - prefixLength
            val numHosts = (1 shl hostBits) - 2 // Exclude network address and broadcast address
            
            // Limit scan to reasonable size (don't scan more than 254 hosts)
            val maxHosts = minOf(numHosts, 254)
            
            
            return (1..maxHosts).map { offset ->
                intToIp(networkInt + offset)
            }
        }
        
        /**
         * Convert integer IP to dotted notation
         */
        private fun intToIp(ip: Int): String {
            return String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                (ip shr 8) and 0xff,
                (ip shr 16) and 0xff,
                (ip shr 24) and 0xff
            )
        }
        
        /**
         * Check if a host is a BluOS player by trying to fetch its status
         */
        private suspend fun checkHost(host: String, port: Int): DiscoveredPlayer? {
            return withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    // First check if host is reachable
                    val address = InetAddress.getByName(host)
                    if (!address.isReachable(TIMEOUT_MS.toInt())) {
                        return@withTimeoutOrNull null
                    }
                    
                    // Try to fetch BluOS status
                    val url = URL("http://$host:$port$STATUS_ENDPOINT")
                    val connection = url.openConnection() as HttpURLConnection
                    
                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = TIMEOUT_MS.toInt()
                        connection.readTimeout = TIMEOUT_MS.toInt()
                        connection.connect()
                        
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            
                            // Parse the response to extract player info
                            val playerInfo = parsePlayerInfo(response)
                            if (playerInfo != null) {
                                return@withTimeoutOrNull DiscoveredPlayer(
                                    host = host,
                                    port = port,
                                    name = playerInfo.first,
                                    model = playerInfo.second
                                )
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    // Host is not a BluOS player or not reachable, silently skip
                    // Log.v(TAG, "Host $host is not reachable or not a BluOS player")
                }
                
                return@withTimeoutOrNull null
            }
        }
        
        /**
         * Parse BluOS status XML/JSON response to extract player name and model
         */
        private fun parsePlayerInfo(response: String): Pair<String, String?>? {
            return try {
                // BluOS returns XML, try to extract name
                val nameRegex = """<name>([^<]+)</name>""".toRegex()
                val modelRegex = """<modelName>([^<]+)</modelName>""".toRegex()
                
                val nameMatch = nameRegex.find(response)
                val modelMatch = modelRegex.find(response)
                
                val name = nameMatch?.groupValues?.get(1)
                val model = modelMatch?.groupValues?.get(1)
                
                if (name != null) {
                    Pair(name, model)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing player info", e)
                null
            }
        }
    }
}
