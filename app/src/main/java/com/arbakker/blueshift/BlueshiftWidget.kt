package com.arbakker.blueshift

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.Html
import android.util.Log
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
        const val ACTION_PLAY_PRESET = "com.arbakker.blueshift.ACTION_PLAY_PRESET"
        const val ACTION_PLAYER_SELECTED = "com.arbakker.blueshift.ACTION_PLAYER_SELECTED"
        const val ACTION_SWITCH_PLAYER = "com.arbakker.blueshift.ACTION_SWITCH_PLAYER"
        const val ACTION_PLAY_PAUSE = "com.arbakker.blueshift.ACTION_PLAY_PAUSE"
        const val ACTION_REFRESH = "com.arbakker.blueshift.ACTION_REFRESH"
        const val ACTION_COPY_NOW_PLAYING = "com.arbakker.blueshift.ACTION_COPY_NOW_PLAYING"
        const val EXTRA_PRESET_ID = "preset_id"
        const val EXTRA_PRESET_NAME = "preset_name"
        const val EXTRA_PLAYER_ID = "player_id"

    // Holds the most recent artist/track string derived when updating now_playing.
    // This is what the copy-to-clipboard action will use, so it always matches
    // what the user actually sees in the widget.
    @Volatile
    var lastNowPlayingForClipboard: String? = null

    fun refreshWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, BlueshiftWidget::class.java)
            )

            if (appWidgetIds.isEmpty()) {
                return
            }

            // notifyAppWidgetViewDataChanged is deprecated at API 31+ but still functional.
            // Modern replacement requires significant refactoring for minimal benefit.
            @Suppress("DEPRECATION")
            for (id in appWidgetIds) {
                appWidgetManager.notifyAppWidgetViewDataChanged(id, R.id.stations_list)
            }

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

        val action = intent.action
        Log.d("BlueshiftWidget", "onReceive action=$action")

        when (action) {
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                refreshWidgets(context)
            }
            ACTION_PLAY_PRESET -> {
                val presetId = intent.getStringExtra(EXTRA_PRESET_ID)
                val presetName = intent.getStringExtra(EXTRA_PRESET_NAME)
                
                if (presetId != null && presetName != null) {
                    playPreset(context, presetId, presetName)
                }
            }
            ACTION_SWITCH_PLAYER -> {
                Log.d("BlueshiftWidget", "ACTION_SWITCH_PLAYER received")
                switchToNextPlayer(context)
            }
            ACTION_PLAY_PAUSE -> {
                Log.d("BlueshiftWidget", "ACTION_PLAY_PAUSE received")
                togglePlayPause(context)
            }
            ACTION_COPY_NOW_PLAYING -> {
                Log.d("BlueshiftWidget", "ACTION_COPY_NOW_PLAYING received")
                copyNowPlayingToClipboard(context)
            }
            ACTION_PLAYER_SELECTED -> {
                val playerId = intent.getStringExtra(EXTRA_PLAYER_ID)
                if (playerId != null) {
                    ConfigManager.setSelectedPlayer(context, playerId)
                    refreshWidgets(context)
                }
            }
            ACTION_REFRESH -> {
                Log.d("BlueshiftWidget", "ACTION_REFRESH received")
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

    private fun copyNowPlayingToClipboard(context: Context) {
        val text = lastNowPlayingForClipboard
        if (text.isNullOrBlank()) {
            return
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Now playing", text)
        clipboard.setPrimaryClip(clip)
    }
    
    private fun playPreset(context: Context, presetId: String, presetName: String) {
        
        // Show immediate feedback in the widget while the preset is starting
        showLoadingNowPlaying(context, presetName)

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
                Log.e("BlueshiftWidget", "Error playing preset", e)
            }
        }
        
        // Update widget info after a delay (in a separate coroutine to not block playback)
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000) // 3 second delay to allow stream to start
            refreshWidgets(context)
        }
    }

    private fun showLoadingNowPlaying(context: Context, presetName: String) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, BlueshiftWidget::class.java)
        )

        if (appWidgetIds.isEmpty()) return

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.blueshift_widget)
            val message = "Loading: $presetName…"
            views.setTextViewText(R.id.now_playing, message)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
    
    private fun switchToNextPlayer(context: Context) {
        // Get current network
        val currentSSID = NetworkDetector.getCurrentWiFiSSID(context)
        val currentNetwork = if (currentSSID != null) {
            ConfigManager.getNetworkProfiles(context)
                .firstOrNull { it.ssidOrSubnet == currentSSID }
        } else {
            null
        }
        
        // If no network or no players, just refresh to show disconnected state
        if (currentNetwork == null) {
            refreshWidgets(context)
            return
        }
        
        // Get players for current network
        val players = ConfigManager.getPlayersForNetwork(context, currentNetwork.id)
        
        if (players.isEmpty()) {
            refreshWidgets(context)
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
                Log.e("BlueshiftWidget", "Error toggling play/pause", e)
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
        Log.e("BlueshiftWidget", "Error sending BluOS command", e)
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
    val title1 = title1Regex.find(xml)?.groupValues?.get(1)?.let { decodeXmlString(it) }
    val title2 = title2Regex.find(xml)?.groupValues?.get(1)?.let { decodeXmlString(it) }
    val title3 = title3Regex.find(xml)?.groupValues?.get(1)?.let { decodeXmlString(it) }
    val artist = artistRegex.find(xml)?.groupValues?.get(1)?.let { decodeXmlString(it) }
    val album = albumRegex.find(xml)?.groupValues?.get(1)?.let { decodeXmlString(it) }
    
    return PlayerStatus(state, title1, title2, title3, artist, album)
}

/**
 * Decode XML/HTML entities in text coming from the BluOS Status XML.
 *
 * We delegate to Android's Html.fromHtml to handle a wide range of entities
 * instead of maintaining our own replacement table.
 */
internal fun decodeXmlString(text: String): String {
    @Suppress("DEPRECATION")
    val spanned = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
    return spanned.toString()
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
    
    // Check current network and available players
    val currentSSID = NetworkDetector.getCurrentWiFiSSID(context)
    val currentNetwork = if (currentSSID != null) {
        ConfigManager.getNetworkProfiles(context)
            .firstOrNull { it.ssidOrSubnet == currentSSID }
    } else {
        null
    }
    
    val availablePlayers = if (currentNetwork != null) {
        ConfigManager.getPlayersForNetwork(context, currentNetwork.id)
    } else {
        emptyList()
    }
    
    // If no WiFi network with players, show message
    if (currentNetwork == null || availablePlayers.isEmpty()) {
        Log.d(
            "BlueshiftWidget",
            "Empty state: currentSSID=$currentSSID, currentNetwork=${currentNetwork?.id}, availablePlayers=${availablePlayers.size}"
        )
        views.setViewVisibility(R.id.stations_list, android.view.View.GONE)
        views.setViewVisibility(R.id.player_selector_container, android.view.View.GONE)
        views.setViewVisibility(R.id.play_pause_button, android.view.View.GONE)
        
        val message = when {
            currentSSID == null -> "Not connected to WiFi"
            currentNetwork == null -> "No players for: $currentSSID"
            else -> "No players for this network"
        }
        
        views.setTextViewText(R.id.player_indicator, message)
        
        // When on WiFi but no players, clearly guide the user.
        // When not on WiFi, emphasize that tapping will refresh.
        val nowPlayingMessage = if (currentSSID != null) {
            "No players found. Tap here to refresh."
        } else {
            "Not connected. Tap here to refresh."
        }
        views.setTextViewText(R.id.now_playing, nowPlayingMessage)
        
        // Set up refresh action for player indicator and now playing (tap to refresh)
        val refreshIntent = Intent(context, BlueshiftWidget::class.java).apply {
            action = BlueshiftWidget.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId + 3000,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.player_indicator, refreshPendingIntent)
        views.setOnClickPendingIntent(R.id.now_playing, refreshPendingIntent)
        
    // Keep settings button visible and functional (open SettingsActivity directly)
    val settingsIntent = Intent(context, SettingsActivity::class.java)
        val settingsPendingIntent = PendingIntent.getActivity(
            context,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.settings_button, settingsPendingIntent)
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
        return
    }
    
    // Normal operation - show widget UI
    views.setViewVisibility(R.id.stations_list, android.view.View.VISIBLE)
    views.setViewVisibility(R.id.player_selector_container, android.view.View.VISIBLE)
    views.setViewVisibility(R.id.play_pause_button, android.view.View.VISIBLE)
    
    // Set up the ListView with RemoteViewsService
    // setRemoteAdapter is deprecated at API 31+ but still functional.
    // Modern replacement (RemoteViews.RemoteCollectionItems) requires API 31+.
    val serviceIntent = Intent(context, StationListRemoteViewsService::class.java)
    serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    @Suppress("DEPRECATION")
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

                // Build artist/track-only text for clipboard, without station name.
                BlueshiftWidget.lastNowPlayingForClipboard = if (status != null &&
                    (status.state == "play" || status.state == "stream")
                ) {
                    val track = status.title2 ?: status.title1
                    val artist = status.artist

                    when {
                        !artist.isNullOrBlank() && !track.isNullOrBlank() -> "${artist} - ${track}"
                        !track.isNullOrBlank() -> track
                        !artist.isNullOrBlank() -> artist
                        else -> null
                    }
                } else {
                    null
                }
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
                Log.e("BlueshiftWidget", "Error updating now playing", e)
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

    // Set up copy-now-playing button
    val copyIntent = Intent(context, BlueshiftWidget::class.java).apply {
        action = BlueshiftWidget.ACTION_COPY_NOW_PLAYING
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    val copyPendingIntent = PendingIntent.getBroadcast(
        context,
        appWidgetId + 4000,
        copyIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.copy_now_playing_button, copyPendingIntent)
    
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
    
    // Set up switch player button - attach to entire player selector container
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
    views.setOnClickPendingIntent(R.id.player_selector_container, switchPlayerPendingIntent)
    
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
    // notifyAppWidgetViewDataChanged is deprecated at API 31+ but still functional.
    @Suppress("DEPRECATION")
    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.stations_list)
}
