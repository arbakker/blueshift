package com.arbakker.blueshift

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Implementation of App Widget functionality.
 * App Widget Configuration implemented in [BlueshiftWidgetConfigureActivity]
 */
class BlueshiftWidget : AppWidgetProvider() {
    
    companion object {
        const val ACTION_PLAY_STREAM = "com.arbakker.blueshift.ACTION_PLAY_STREAM"
        const val ACTION_PLAY_PRESET = "com.arbakker.blueshift.ACTION_PLAY_PRESET"
        const val ACTION_PLAYER_SELECTED = "com.arbakker.blueshift.ACTION_PLAYER_SELECTED"
        const val ACTION_SWITCH_PLAYER = "com.arbakker.blueshift.ACTION_SWITCH_PLAYER"
        const val ACTION_PLAY_PAUSE = "com.arbakker.blueshift.ACTION_PLAY_PAUSE"
        const val ACTION_REFRESH = "com.arbakker.blueshift.ACTION_REFRESH"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_STREAM_NAME = "stream_name"
        const val EXTRA_PRESET_ID = "preset_id"
        const val EXTRA_PRESET_NAME = "preset_name"
        const val EXTRA_PLAYER_ID = "player_id"

        fun refreshWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, BlueshiftWidget::class.java)
            )

            if (appWidgetIds.isEmpty()) {
                return
            }

            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.stations_list)

            val updateIntent = Intent(context, BlueshiftWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(updateIntent)
        }
    }
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        
        when (intent.action) {
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                refreshWidgets(context)
            }
            ACTION_PLAY_PRESET -> {
                val presetId = intent.getStringExtra(EXTRA_PRESET_ID)
                val presetName = intent.getStringExtra(EXTRA_PRESET_NAME)
                
                
                if (presetId != null && presetName != null) {
                    playPreset(context, presetId, presetName)
                } else {
                }
            }
            ACTION_PLAY_STREAM -> {
                val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
                val streamName = intent.getStringExtra(EXTRA_STREAM_NAME)
                
                
                if (streamUrl != null && streamName != null) {
                    playStream(context, streamUrl, streamName)
                } else {
                }
            }
            ACTION_SWITCH_PLAYER -> {
                switchToNextPlayer(context)
            }
            ACTION_PLAY_PAUSE -> {
                togglePlayPause(context)
            }
            ACTION_PLAYER_SELECTED -> {
                val playerId = intent.getStringExtra(EXTRA_PLAYER_ID)
                if (playerId != null) {
                    ConfigManager.setSelectedPlayer(context, playerId)
                    refreshWidgets(context)
                }
            }
            ACTION_REFRESH -> {
                refreshWidgets(context)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // When the user deletes the widget, delete the preference associated with it.
        for (appWidgetId in appWidgetIds) {
            deleteTitlePref(context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }
    
    private fun playStream(context: Context, streamUrl: String, streamName: String) {
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val player = ConfigManager.getSelectedPlayer(context)
                if (player == null) {
                    // No player selected, can't play
                    return@launch
                }
                
                
                // Use BluOS Play?url= endpoint to play stream directly
                val playUrl = "/Play?url=${java.net.URLEncoder.encode(streamUrl, "UTF-8")}"
                val fullUrl = player.url + playUrl
                
                sendBluesoundCommand(fullUrl)
                
            } catch (e: Exception) {
            }
        }
        
        // Update widget info after a delay (in a separate coroutine to not block playback)
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000) // 3 second delay to allow stream to start
            refreshWidgets(context)
        }
    }
    
    private fun playPreset(context: Context, presetId: String, presetName: String) {
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val player = ConfigManager.getSelectedPlayer(context)
                if (player == null) {
                    return@launch
                }
                
                
                // Get the preset from ConfigManager
                val presets = ConfigManager.getPresetsForPlayer(context, player.id)
                val preset = presets.find { it.id == presetId }
                
                if (preset == null) {
                    return@launch
                }
                
                // Use BluOS /Preset?id= endpoint to play the preset
                val remotePresetId = preset.remoteId
                if (remotePresetId.isBlank()) {
                    return@launch
                }

                val playUrl = "/Preset?id=${java.net.URLEncoder.encode(remotePresetId, "UTF-8")}" 
                val fullUrl = player.url + playUrl
                
                sendBluesoundCommand(fullUrl)
                
            } catch (e: Exception) {
            }
        }
        
        // Update widget info after a delay (in a separate coroutine to not block playback)
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000) // 3 second delay to allow stream to start
            refreshWidgets(context)
        }
    }
    
    private fun switchToNextPlayer(context: Context) {
        val players = ConfigManager.getPlayers(context)
        if (players.isEmpty()) {
            return
        }
        
        val currentPlayer = ConfigManager.getSelectedPlayer(context)
        val currentIndex = players.indexOfFirst { it.id == currentPlayer?.id }
        
        // Switch to next player (cycle back to first if at end)
        val nextIndex = (currentIndex + 1) % players.size
        val nextPlayer = players[nextIndex]
        
        
        ConfigManager.setSelectedPlayer(context, nextPlayer.id)
        refreshWidgets(context)
    }
    
    private fun togglePlayPause(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val player = ConfigManager.getSelectedPlayer(context)
                if (player == null) {
                    return@launch
                }
                
                
                // Get current player status
                val status = getPlayerStatus(player)
                val state = status?.state
                
                // Toggle between play and pause based on current state
                val command = if (state == "play" || state == "stream") {
                    "/Pause"
                } else {
                    "/Play"
                }
                
                val fullUrl = player.url + command
                sendBluesoundCommand(fullUrl)
                
                // Wait a bit then refresh widget to update button icon
                kotlinx.coroutines.delay(500)
                refreshWidgets(context)
                
            } catch (e: Exception) {
            }
        }
    }
    
    private fun sendBluesoundCommand(fullUrl: String) {
        val url = URL(fullUrl)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            
            val responseCode = connection.responseCode
            // Read response if needed for debugging
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Query player status from BluOS /Status endpoint
 */
internal fun getPlayerStatus(player: Player): PlayerStatus? {
    return try {
        val url = URL("${player.url}/Status")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.connect()
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parsePlayerStatus(response)
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse BluOS Status XML response
 */
internal fun parsePlayerStatus(xml: String): PlayerStatus {
    val stateRegex = """<state>([^<]+)</state>""".toRegex()
    val title1Regex = """<title1>([^<]+)</title1>""".toRegex()
    val title2Regex = """<title2>([^<]+)</title2>""".toRegex()
    val title3Regex = """<title3>([^<]+)</title3>""".toRegex()
    val artistRegex = """<artist>([^<]+)</artist>""".toRegex()
    val albumRegex = """<album>([^<]+)</album>""".toRegex()
    
    val state = stateRegex.find(xml)?.groupValues?.get(1) ?: "stop"
    val title1 = title1Regex.find(xml)?.groupValues?.get(1)?.let { decodeXmlEntities(it) }
    val title2 = title2Regex.find(xml)?.groupValues?.get(1)?.let { decodeXmlEntities(it) }
    val title3 = title3Regex.find(xml)?.groupValues?.get(1)?.let { decodeXmlEntities(it) }
    val artist = artistRegex.find(xml)?.groupValues?.get(1)?.let { decodeXmlEntities(it) }
    val album = albumRegex.find(xml)?.groupValues?.get(1)?.let { decodeXmlEntities(it) }
    
    return PlayerStatus(state, title1, title2, title3, artist, album)
}

/**
 * Decode XML entities like &amp;, &lt;, &gt;, &quot;, &apos;
 */
internal fun decodeXmlEntities(text: String): String {
    return text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
}

/**
 * Format player status into a readable now playing text
 */
internal fun formatNowPlayingText(status: PlayerStatus?): String {
    if (status == null) {
        return "-"
    }
    
    return when (status.state) {
        "pause" -> "Paused"
        "stop" -> "-"
        "play", "stream" -> {
            // For streaming radio: combine title1 (station name) with title2 (current track)
            // Note: title3 contains the previous track, which we ignore
            // For local playback: use artist and title1
            
            if (!status.title1.isNullOrBlank() && !status.title2.isNullOrBlank()) {
                // Radio stream: show "Station Name - Current Track"
                "${status.title1} - ${status.title2}"
            } else if (!status.title2.isNullOrBlank()) {
                // Streaming without title1, just show current track
                status.title2
            } else if (!status.artist.isNullOrBlank() && !status.title1.isNullOrBlank()) {
                // Local playback: use artist and title
                "${status.artist} - ${status.title1}"
            } else if (!status.title1.isNullOrBlank()) {
                // Fallback to just title1
                status.title1
            } else {
                "-"
            }
        }
        else -> "-"
    }
}

internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    
    val views = RemoteViews(context.packageName, R.layout.blueshift_widget)
    
    // Set up the ListView with RemoteViewsService
    val serviceIntent = Intent(context, StationListRemoteViewsService::class.java)
    views.setRemoteAdapter(R.id.stations_list, serviceIntent)
    
    
    // Set up click template for list items
    val clickIntent = Intent(context, BlueshiftWidget::class.java).apply {
        action = BlueshiftWidget.ACTION_PLAY_PRESET
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }   
    val clickPendingIntent = PendingIntent.getBroadcast(
        context,
        appWidgetId, // Use widget ID as request code to make it unique
        clickIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
    views.setPendingIntentTemplate(R.id.stations_list, clickPendingIntent)
    
    
    // Set up player spinner (simplified - showing selected player name)
    val selectedPlayer = ConfigManager.getSelectedPlayer(context)
    
    // Set icon tints programmatically based on current theme
    // Check if dark mode is enabled
    val isDarkMode = (context.resources.configuration.uiMode and 
                      android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                      android.content.res.Configuration.UI_MODE_NIGHT_YES
    val iconColor = if (isDarkMode) {
        android.graphics.Color.WHITE
    } else {
        android.graphics.Color.BLACK
    }
    views.setInt(R.id.settings_button, "setColorFilter", iconColor)
    views.setInt(R.id.switch_player_button, "setColorFilter", iconColor)
    views.setInt(R.id.play_pause_button, "setColorFilter", iconColor)
    
    // Set player indicator with just the player name
    if (selectedPlayer != null) {
        views.setTextViewText(R.id.player_indicator, selectedPlayer.label)
        
        // Query player status asynchronously and update now playing info and play/pause button
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = getPlayerStatus(selectedPlayer)
                val nowPlayingText = formatNowPlayingText(status)
                val state = status?.state
                
                // Update play/pause button icon based on player state
                val iconRes = if (state == "play" || state == "stream") {
                    R.drawable.ic_pause
                } else {
                    R.drawable.ic_play
                }
                views.setImageViewResource(R.id.play_pause_button, iconRes)
                views.setInt(R.id.play_pause_button, "setColorFilter", iconColor)
                
                // Update the widget with the now playing info
                views.setTextViewText(R.id.now_playing, nowPlayingText)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
            }
        }
    } else {
        views.setTextViewText(R.id.player_indicator, "⚠ No player selected")
        views.setTextViewText(R.id.now_playing, "-")
    }
    
    // Set up refresh action for now_playing text (tap to refresh)
    val refreshIntent = Intent(context, BlueshiftWidget::class.java).apply {
        action = BlueshiftWidget.ACTION_REFRESH
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    val refreshPendingIntent = PendingIntent.getBroadcast(
        context,
        appWidgetId + 3000, // Different request code
        refreshIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.now_playing, refreshPendingIntent)
    
    // Set up play/pause button
    val playPauseIntent = Intent(context, BlueshiftWidget::class.java).apply {
        action = BlueshiftWidget.ACTION_PLAY_PAUSE
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    val playPausePendingIntent = PendingIntent.getBroadcast(
        context,
        appWidgetId + 2000, // Different request code
        playPauseIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.play_pause_button, playPausePendingIntent)
    
    // Set up switch player button
    val switchPlayerIntent = Intent(context, BlueshiftWidget::class.java).apply {
        action = BlueshiftWidget.ACTION_SWITCH_PLAYER
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    val switchPlayerPendingIntent = PendingIntent.getBroadcast(
        context,
        appWidgetId + 1000, // Different request code from station clicks
        switchPlayerIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.switch_player_button, switchPlayerPendingIntent)
    
    // Set up settings button click to open SettingsActivity
    val settingsIntent = Intent(context, SettingsActivity::class.java)
    val settingsPendingIntent = PendingIntent.getActivity(
        context,
        0,
        settingsIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.settings_button, settingsPendingIntent)
    
    // Instruct the widget manager to update the widget
    appWidgetManager.updateAppWidget(appWidgetId, views)
    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.stations_list)
}
