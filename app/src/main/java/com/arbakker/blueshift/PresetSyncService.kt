package com.arbakker.blueshift

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object PresetSyncService {
    private const val TAG = "PresetSyncService"
    private const val PRESETS_ENDPOINT = "/Presets"
    private const val TIMEOUT_MS = 5000
    
    /**
     * Fetch presets from a BluOS player
     */
    suspend fun fetchPresetsFromPlayer(player: Player): List<BluOSPreset> = withContext(Dispatchers.IO) {
        val presets = mutableListOf<BluOSPreset>()
        
        try {
            val url = URL("${player.url}$PRESETS_ENDPOINT")
            val connection = url.openConnection() as HttpURLConnection
            
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.connect()
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    presets.addAll(parsePresetsXml(response, player.id))
                } else {
                    Log.e(TAG, "Failed to fetch presets from ${player.label}: HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching presets from ${player.label}", e)
        }
        
        return@withContext presets
    }
    
    /**
     * Fetch presets from all configured players
     */
    suspend fun syncAllPresets(context: Context) = withContext(Dispatchers.IO) {
        
        val players = ConfigManager.getPlayers(context)
        val allPresets = mutableListOf<BluOSPreset>()
        
        for (player in players) {
            val presets = fetchPresetsFromPlayer(player)
            allPresets.addAll(presets)
        }
        
        // Save presets to config
        ConfigManager.savePresets(context, allPresets)
        
    }
    
    /**
     * Parse BluOS presets XML response
     */
    private fun parsePresetsXml(xml: String, playerId: String): List<BluOSPreset> {
        val presets = mutableListOf<BluOSPreset>()
        
        try {
            // Extract preset elements using regex
            val presetRegex = """<preset\s+id="([^"]+)"\s+name="([^"]+)"\s+url="([^"]+)"(?:\s+image="([^"]+)")?[^>]*>""".toRegex()
            
            presetRegex.findAll(xml).forEach { match ->
                val id = match.groupValues[1]
                val name = match.groupValues[2]
                val url = match.groupValues[3]
                val image = match.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }
                
                // Decode HTML entities
                val decodedName = name
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                
                val decodedUrl = url
                    .replace("&amp;", "&")
                
                presets.add(
                    BluOSPreset(
                        id = "${playerId}_$id",
                        remoteId = id,
                        name = decodedName,
                        url = decodedUrl,
                        image = image,
                        playerId = playerId
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing presets XML", e)
        }
        
        return presets
    }
}
