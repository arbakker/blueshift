package com.arbakker.blueshift

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.util.Locale

class StationListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return StationListRemoteViewsFactory(this.applicationContext, intent)
    }
}

class StationListRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<WidgetItem> = emptyList()
    private var cachedItems: List<WidgetItem> = emptyList()

    override fun onCreate() {
        items = buildWidgetItems()
        cachedItems = items
    }

    override fun onDataSetChanged() {
        // Build new items but keep old cache until complete
        val newItems = buildWidgetItems()
        cachedItems = items // Save old items
        items = newItems
    }

    override fun onDestroy() {
        items = emptyList()
        cachedItems = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        val item = items.getOrNull(position) ?: return null
        return when (item) {
            is WidgetItem.PresetItem -> {
                val preset = item.preset
                try {
                    // Use compact layout if enabled
                    val layoutId = if (ConfigManager.isCompactMode(context)) {
                        R.layout.widget_station_item_compact
                    } else {
                        R.layout.widget_station_item
                    }
                    val rv = RemoteViews(context.packageName, layoutId)
                    rv.setTextViewText(R.id.station_name, preset.name)
                    val fillInIntent = Intent().apply {
                        putExtra(BlueshiftWidget.EXTRA_PRESET_ID, preset.id)
                        putExtra(BlueshiftWidget.EXTRA_PRESET_NAME, preset.name)
                    }
                    rv.setOnClickFillInIntent(R.id.station_name, fillInIntent)
                    rv
                } catch (e: Exception) {
                    android.util.Log.e("StationListService", "Error creating RemoteViews for position $position", e)
                    null
                }
            }
        }
    }

    override fun getLoadingView(): RemoteViews? {
        // Return null to use default loading behavior
        // This prevents "Loading..." from appearing as placeholder items
        return null
    }

    // Two view types: regular and compact
    // Note: All items use the same view type at any given time based on compact mode setting
    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long =
        when (val item = items.getOrNull(position)) {
            is WidgetItem.PresetItem -> item.preset.id.hashCode().toLong()
            else -> position.toLong()
        }

    override fun hasStableIds(): Boolean = true

    private fun buildWidgetItems(): List<WidgetItem> {
        val selectedPlayer = ConfigManager.getSelectedPlayer(context) ?: return emptyList()
        val playerPresets = ConfigManager.getPresetsForPlayer(context, selectedPlayer.id)
        val ordering = ConfigManager.getPresetOrdering(context)
        
        if (ordering != PresetOrdering.ALPHABETICAL) {
            return playerPresets.map { WidgetItem.PresetItem(it) }
        }
        val (inputs, streams) = playerPresets.partition { it.url.startsWith("Capture:") }
        
        val sortedStreams = streams.sortedWith(compareBy { normalizePresetName(it.name) })
        val sortedInputs = inputs.sortedWith(compareBy { normalizePresetName(it.name) })
        val result = mutableListOf<WidgetItem>()
        result.addAll(sortedStreams.map { WidgetItem.PresetItem(it) })
        result.addAll(sortedInputs.map { WidgetItem.PresetItem(it) })
        return result
    }

    private fun normalizePresetName(name: String): String {
        val trimmed = name.replaceFirst("^[^\\p{L}]+".toRegex(), "").ifBlank { name }
        return trimmed.lowercase(Locale.getDefault())
    }
}

sealed class WidgetItem {
    data class PresetItem(val preset: BluOSPreset) : WidgetItem()
}
