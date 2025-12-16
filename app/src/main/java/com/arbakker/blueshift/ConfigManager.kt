package com.arbakker.blueshift

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ConfigManager {
    private const val PREFS_NAME = "blueshift_config"
    private const val KEY_PLAYERS = "players"
    private const val KEY_PRESETS = "presets"
    private const val KEY_SELECTED_PLAYER = "selected_player"
    private const val KEY_LAST_PRESET_SYNC = "last_preset_sync"
    private const val KEY_PRESET_ORDERING = "preset_ordering"
    
    private val gson = Gson()
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // Players
    fun getPlayers(context: Context): List<Player> {
        val json = getPrefs(context).getString(KEY_PLAYERS, null)
        if (json.isNullOrEmpty()) {
            return emptyList()
        }
        return try {
            val type = object : TypeToken<List<Player>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun savePlayers(context: Context, players: List<Player>) {
        val json = gson.toJson(players)
        getPrefs(context).edit().putString(KEY_PLAYERS, json).apply()
    }
    
    fun addPlayer(context: Context, player: Player) {
        val players = getPlayers(context).toMutableList()
        val isFirstPlayer = players.isEmpty()
        players.add(player)
        savePlayers(context, players)
        
        // Auto-select if this is the first player
        if (isFirstPlayer) {
            setSelectedPlayer(context, player.id)
        }
    }
    
    fun deletePlayer(context: Context, playerId: String) {
        val players = getPlayers(context).filter { it.id != playerId }
        savePlayers(context, players)
    }
    
    // Selected Player
    fun getSelectedPlayer(context: Context): Player? {
        val playerId = getPrefs(context).getString(KEY_SELECTED_PLAYER, null)
        if (playerId == null) {
            // First time - auto-select first available player if any exist
            val players = getPlayers(context)
            val firstPlayer = players.firstOrNull()
            if (firstPlayer != null) {
                setSelectedPlayer(context, firstPlayer.id)
                return firstPlayer
            }
            return null
        }
        return getPlayers(context).find { it.id == playerId }
    }
    
    fun setSelectedPlayer(context: Context, playerId: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_PLAYER, playerId).apply()
    }
    
    // BluOS Presets
    fun getPresets(context: Context): List<BluOSPreset> {
        val json = getPrefs(context).getString(KEY_PRESETS, null) ?: return emptyList()
        val type = object : TypeToken<List<BluOSPreset>>() {}.type
        return gson.fromJson(json, type)
    }
    
    fun savePresets(context: Context, presets: List<BluOSPreset>) {
        val json = gson.toJson(presets)
        getPrefs(context).edit()
            .putString(KEY_PRESETS, json)
            .putLong(KEY_LAST_PRESET_SYNC, System.currentTimeMillis())
            .apply()
    }
    
    fun getPresetsForPlayer(context: Context, playerId: String): List<BluOSPreset> {
        return getPresets(context).filter { it.playerId == playerId }
    }
    
    fun getLastPresetSyncTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_PRESET_SYNC, 0)
    }
    
    fun needsPresetSync(context: Context): Boolean {
        val lastSync = getLastPresetSyncTime(context)
        val now = System.currentTimeMillis()
        val hoursSinceSync = (now - lastSync) / (1000 * 60 * 60)
        // Sync if never synced or if more than 24 hours
        return lastSync == 0L || hoursSinceSync > 24
    }

    fun getPresetOrdering(context: Context): PresetOrdering {
        val ordinal = getPrefs(context).getString(KEY_PRESET_ORDERING, null)
        return ordinal?.let {
            try {
                PresetOrdering.valueOf(it)
            } catch (_: IllegalArgumentException) {
                PresetOrdering.ID_ORDER
            }
        } ?: PresetOrdering.ID_ORDER
    }

    fun setPresetOrdering(context: Context, ordering: PresetOrdering) {
        getPrefs(context).edit().putString(KEY_PRESET_ORDERING, ordering.name).apply()
    }
}
