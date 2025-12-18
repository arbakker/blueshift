package com.arbakker.blueshift

import android.content.Context
import android.util.Log
import android.text.Html
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
     * Fetch presets from specific players
     */
    suspend fun syncPlayersPresets(context: Context, players: List<Player>) = withContext(Dispatchers.IO) {
        val allPresets = ConfigManager.getPresets(context).toMutableList()
        
        // Remove presets for these players first
        val playerIds = players.map { it.id }.toSet()
        allPresets.removeAll { it.playerId in playerIds }
        
        // Fetch fresh presets for the specified players
        for (player in players) {
            val presets = fetchPresetsFromPlayer(player)
            allPresets.addAll(presets)
        }
        
        // Save updated presets to config
        ConfigManager.savePresets(context, allPresets)
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
                
                // Decode entities using Android's Html.fromHtml to cover a wide range
                // of XML/HTML encodings (e.g. &amp;, &#39;, etc.).
                @Suppress("DEPRECATION")
                val decodedName = Html.fromHtml(name, Html.FROM_HTML_MODE_LEGACY).toString()

                // For URLs we just need &amp; to become &; other entities are uncommon here.
                val decodedUrl = url.replace("&amp;", "&")
                
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
