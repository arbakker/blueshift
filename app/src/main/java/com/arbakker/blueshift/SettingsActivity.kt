package com.arbakker.blueshift

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.Toast
import android.app.ProgressDialog
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var playersList: ListView
    private lateinit var discoverPlayersButton: Button
    private lateinit var addPlayerButton: Button
    private lateinit var manualSyncButton: Button
    private lateinit var exportPresetsButton: Button
    private lateinit var presetOrderSpinner: Spinner
    
    private lateinit var playersAdapter: ArrayAdapter<String>
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        title = "Blueshift Settings"
        
    playersList = findViewById(R.id.players_list)
    discoverPlayersButton = findViewById(R.id.discover_players_button)
    addPlayerButton = findViewById(R.id.add_player_button)
    manualSyncButton = findViewById(R.id.manual_sync_button)
    exportPresetsButton = findViewById(R.id.export_presets_button)
    presetOrderSpinner = findViewById(R.id.preset_order_spinner)
        
        // Hide discovery button if feature is disabled
        if (!FeatureFlags.ENABLE_PLAYER_DISCOVERY) {
            discoverPlayersButton.visibility = android.view.View.GONE
        }
        
        // Hide export button if feature is disabled
        if (!FeatureFlags.ENABLE_PRESET_EXPORT) {
            exportPresetsButton.visibility = android.view.View.GONE
        }
        
    setupPlayers()
        
    setupPresetOrderSpinner()

        manualSyncButton.setOnClickListener { syncPresetsManually() }
        exportPresetsButton.setOnClickListener { exportPresetsToM3U() }
        discoverPlayersButton.setOnClickListener { discoverPlayers() }
        addPlayerButton.setOnClickListener { showAddPlayerDialog() }
    }
    
    private fun setupPlayers() {
        val players = ConfigManager.getPlayers(this)
        
        val playerNames = players.map { player ->
            "${player.label} (${player.host}:${player.port})"
        }
        
        playersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, playerNames.toMutableList())
        playersList.adapter = playersAdapter
        
        playersList.setOnItemClickListener { _, _, position, _ ->
            val player = players[position]
            showPlayerOptionsDialog(player)
        }
    }

    private fun setupPresetOrderSpinner() {
        val options = listOf(
            getString(R.string.preset_order_default),
            getString(R.string.preset_order_alphabetical)
        )

        ArrayAdapter(this, android.R.layout.simple_spinner_item, options).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            presetOrderSpinner.adapter = adapter
        }

        val currentOrdering = ConfigManager.getPresetOrdering(this)
        var spinnerReady = false

        presetOrderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!spinnerReady) {
                    return
                }

                val selectedOrdering = if (position == 1) {
                    PresetOrdering.ALPHABETICAL
                } else {
                    PresetOrdering.ID_ORDER
                }

                if (ConfigManager.getPresetOrdering(this@SettingsActivity) != selectedOrdering) {
                    ConfigManager.setPresetOrdering(this@SettingsActivity, selectedOrdering)
                    BlueshiftWidget.refreshWidgets(this@SettingsActivity)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        presetOrderSpinner.setSelection(currentOrdering.ordinal)
        spinnerReady = true
    }
    
    private fun showAddPlayerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_player, null)
        val labelEdit = dialogView.findViewById<EditText>(R.id.player_label)
        val hostEdit = dialogView.findViewById<EditText>(R.id.player_host)
        val portEdit = dialogView.findViewById<EditText>(R.id.player_port)
        
        portEdit.setText("11000")
        
        AlertDialog.Builder(this)
            .setTitle("Add Player")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val label = labelEdit.text.toString()
                val host = hostEdit.text.toString()
                val port = portEdit.text.toString().toIntOrNull() ?: 11000
                
                if (label.isNotEmpty() && host.isNotEmpty()) {
                    val player = Player(UUID.randomUUID().toString(), label, host, port)
                    ConfigManager.addPlayer(this, player)
                    setupPlayers()
                    BlueshiftWidget.refreshWidgets(this)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showPlayerOptionsDialog(player: Player) {
        AlertDialog.Builder(this)
            .setTitle(player.label)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> showRenamePlayerDialog(player)
                    1 -> {
                        ConfigManager.deletePlayer(this, player.id)
                        setupPlayers()
                        BlueshiftWidget.refreshWidgets(this)
                    }
                }
            }
            .show()
    }
    
    private fun showRenamePlayerDialog(player: Player) {
        val input = EditText(this).apply {
            setText(player.label)
            hint = "Player name"
        }
        
        AlertDialog.Builder(this)
            .setTitle("Rename Player")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val updatedPlayer = player.copy(label = newName)
                    val players = ConfigManager.getPlayers(this).toMutableList()
                    val index = players.indexOfFirst { it.id == player.id }
                    if (index != -1) {
                        players[index] = updatedPlayer
                        ConfigManager.savePlayers(this, players)
                        setupPlayers()
                        BlueshiftWidget.refreshWidgets(this)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun syncPresetsManually() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Syncing BluOS presets...")
            setCancelable(false)
            show()
        }
        manualSyncButton.isEnabled = false
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    PresetSyncService.syncAllPresets(this@SettingsActivity)
                }
                BlueshiftWidget.refreshWidgets(this@SettingsActivity)
                Toast.makeText(
                    this@SettingsActivity,
                    "Presets synchronized successfully",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    "Preset sync failed: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progressDialog.dismiss()
                manualSyncButton.isEnabled = true
            }
        }
    }

    private fun discoverPlayers() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Discovering BluOS players on local network...\nThis may take up to a minute.")
            setCancelable(false)
            show()
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val discoveredPlayers = withContext(Dispatchers.IO) {
                    PlayerDiscovery.discoverPlayers(this@SettingsActivity)
                }
                
                progressDialog.dismiss()
                
                if (discoveredPlayers.isEmpty()) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("No Players Found")
                        .setMessage("No BluOS players were discovered on the local network.\n\nMake sure:\n• Your device is on the same WiFi network as your BluOS players\n• BluOS players are powered on\n• Port 11000 is not blocked by firewall")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showDiscoveredPlayersDialog(discoveredPlayers)
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "Error during discovery: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun showDiscoveredPlayersDialog(discoveredPlayers: List<DiscoveredPlayer>) {
        val playerNames = discoveredPlayers.map { player ->
            "${player.name} (${player.host}:${player.port})${player.model?.let { " - $it" } ?: ""}"
        }.toTypedArray()
        
        val selectedPlayers = BooleanArray(discoveredPlayers.size) { true }
        
        AlertDialog.Builder(this)
            .setTitle("Found ${discoveredPlayers.size} Player(s)")
            .setMultiChoiceItems(playerNames, selectedPlayers) { _, which, isChecked ->
                selectedPlayers[which] = isChecked
            }
            .setPositiveButton("Add Selected") { _, _ ->
                var addedCount = 0
                discoveredPlayers.forEachIndexed { index, discoveredPlayer ->
                    if (selectedPlayers[index]) {
                        val player = Player(
                            id = UUID.randomUUID().toString(),
                            label = discoveredPlayer.name,
                            host = discoveredPlayer.host,
                            port = discoveredPlayer.port
                        )
                        ConfigManager.addPlayer(this, player)
                        addedCount++
                    }
                }
                
                setupPlayers()
                BlueshiftWidget.refreshWidgets(this)
                Toast.makeText(this, "Added $addedCount player(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Export presets to M3U playlist file
     * INCOMPLETE, NON-FUNCTIONAL: This feature is not fully implemented or tested.
     * Requires ENABLE_PRESET_EXPORT feature flag to be enabled.
     */
    private fun exportPresetsToM3U() {
        val presets = ConfigManager.getPresets(this)
        
        if (presets.isEmpty()) {
            Toast.makeText(this, "No presets to export. Sync presets first.", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            // Create M3U content
            val m3uContent = buildString {
                appendLine("#EXTM3U")
                presets.forEach { preset ->
                    appendLine("#EXTINF:-1,${preset.name}")
                    appendLine(preset.url)
                }
            }
            
            // Save to file in cache directory
            val cacheDir = cacheDir
            val m3uFile = File(cacheDir, "blueshift_presets.m3u")
            m3uFile.writeText(m3uContent)
            
            // Share the file using FileProvider
            val fileUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                m3uFile
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/x-mpegurl"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(shareIntent, "Export M3U Playlist"))
            Toast.makeText(this, "Exported ${presets.size} preset(s)", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error exporting presets: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
