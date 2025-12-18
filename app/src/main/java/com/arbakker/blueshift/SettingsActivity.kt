package com.arbakker.blueshift

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class SettingsActivity : AppCompatActivity() {
    
    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
    }
    
    private lateinit var playersList: ListView
    private lateinit var discoverPlayersButton: Button
    private lateinit var addPlayerButton: Button
    private lateinit var manualSyncButton: Button
    private lateinit var presetOrderSpinner: Spinner
    private lateinit var networkSelectorContainer: LinearLayout
    private lateinit var networkSelectorSpinner: Spinner
    private lateinit var compactModeSwitch: androidx.appcompat.widget.SwitchCompat
    
    private lateinit var playersAdapter: ArrayAdapter<String>
    private var currentPlayers: List<Player> = emptyList()
    private var currentNetworkFilter: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Use the built-in DarkActionBar from the app theme; set a clear app + screen title.
        supportActionBar?.title = "Blueshift"

        playersList = findViewById(R.id.players_list)
        discoverPlayersButton = findViewById(R.id.discover_players_button)
        addPlayerButton = findViewById(R.id.add_player_button)
        manualSyncButton = findViewById(R.id.manual_sync_button)
    presetOrderSpinner = findViewById(R.id.preset_order_spinner)
    networkSelectorContainer = findViewById(R.id.network_selector_container)
    networkSelectorSpinner = findViewById(R.id.network_selector_spinner)
    compactModeSwitch = findViewById(R.id.compact_mode_switch)
        
        // Hide discovery button if feature is disabled
        if (!FeatureFlags.ENABLE_PLAYER_DISCOVERY) {
            discoverPlayersButton.visibility = android.view.View.GONE
        }
        
    // Export presets is now only accessible via player options, so no top-level button.

    setupNetworkSelector()
    setupPlayers()
    setupPresetOrderSpinner()
    setupCompactModeSwitch()
    setupAboutSection()

        manualSyncButton.setOnClickListener { syncPresetsManually() }
        discoverPlayersButton.setOnClickListener { discoverPlayers() }
        addPlayerButton.setOnClickListener { showAddPlayerDialog() }
    }
    
    private fun setupNetworkSelector() {
        val networks = ConfigManager.getNetworkProfiles(this)
        
        // Always show network selector for clarity
        networkSelectorContainer.visibility = View.VISIBLE
        
        // Create options: "All Networks" + individual networks
        val options = mutableListOf("All Networks")
        options.addAll(networks.map { network ->
            network.name ?: network.ssidOrSubnet ?: "Network ${network.id.take(8)}"
        })
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        // Temporarily remove listener to prevent triggering during setup
        networkSelectorSpinner.onItemSelectedListener = null
        networkSelectorSpinner.adapter = adapter
        
        // Preserve current filter if it exists, otherwise use default network
        val selectionIndex = if (currentNetworkFilter != null) {
            val index = networks.indexOfFirst { it.id == currentNetworkFilter }
            if (index >= 0) index + 1 else 0 // +1 for "All Networks"
        } else {
            val defaultNetwork = ConfigManager.getDefaultNetwork(this)
            if (defaultNetwork != null) {
                networks.indexOf(defaultNetwork) + 1 // +1 for "All Networks"
            } else {
                0
            }
        }
        networkSelectorSpinner.setSelection(selectionIndex)
        currentNetworkFilter = if (selectionIndex == 0) null else networks[selectionIndex - 1].id
        
        // Re-attach listener after setup is complete
        networkSelectorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentNetworkFilter = if (position == 0) {
                    null // "All Networks"
                } else {
                    networks[position - 1].id
                }
                
                // Update default network       
                currentNetworkFilter?.let { networkId ->
                    ConfigManager.setDefaultNetwork(this@SettingsActivity, networkId)
                }
                
                setupPlayers() // Refresh player list
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupPlayers() {
        currentPlayers = if (currentNetworkFilter == null) {
            ConfigManager.getPlayers(this) // All players
        } else {
            ConfigManager.getPlayersForNetwork(this, currentNetworkFilter)
        }
        
        android.util.Log.d("SettingsActivity", "setupPlayers: currentNetworkFilter=$currentNetworkFilter, players.size=${currentPlayers.size}")
        currentPlayers.forEachIndexed { index, player ->
            android.util.Log.d("SettingsActivity", "  Player $index: ${player.label} (${player.host}:${player.port}) networkId=${player.networkId}")
        }
        
        val playerNames = currentPlayers.map { player ->
            "${player.label} (${player.host}:${player.port})"
        }
        
        android.util.Log.d("SettingsActivity", "playerNames.size=${playerNames.size}")
        playerNames.forEachIndexed { index, name ->
            android.util.Log.d("SettingsActivity", "  Name $index: $name")
        }
        
        playersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, playerNames.toMutableList())
        playersList.adapter = playersAdapter
        
        android.util.Log.d("SettingsActivity", "Adapter set, adapter.count=${playersAdapter.count}")
        
        playersList.setOnItemClickListener { _, _, position, _ ->
            val player = currentPlayers[position]
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
    
    private fun setupCompactModeSwitch() {
        // Set current state
        compactModeSwitch.isChecked = ConfigManager.isCompactMode(this)
        
        // Listen for changes
        compactModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            ConfigManager.setCompactMode(this, isChecked)
            BlueshiftWidget.refreshWidgets(this)
        }
    }
    
    private fun showAddPlayerDialog() {
        // Check if we should request permission first
        if (!NetworkDetector.hasLocationPermission(this)) {
            requestLocationPermissionIfNeeded(
                onGranted = { showAddPlayerDialogInternal() },
                onDenied = { showAddPlayerDialogInternal() }
            )
        } else {
            showAddPlayerDialogInternal()
        }
    }
    
    private fun showAddPlayerDialogInternal() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_player, null)
        val labelEdit = dialogView.findViewById<EditText>(R.id.player_label)
        val hostEdit = dialogView.findViewById<EditText>(R.id.player_host)
        val portEdit = dialogView.findViewById<EditText>(R.id.player_port)
        
        portEdit.setText("11000")
        
        // Detect current WiFi SSID
        val currentSSID = if (NetworkDetector.hasLocationPermission(this)) {
            NetworkDetector.getCurrentWiFiSSID(this)
        } else {
            null
        }
        
        val detectedNetwork = if (currentSSID != null) {
            ConfigManager.findOrCreateNetworkProfile(this, currentSSID)
        } else {
            null
        }
        
        val messageText = when {
            detectedNetwork != null -> "Network detected: ${detectedNetwork.name ?: detectedNetwork.ssidOrSubnet}"
            !NetworkDetector.hasLocationPermission(this) -> "Location permission needed to detect network"
            else -> "No WiFi network detected"
        }
        
        AlertDialog.Builder(this)
            .setTitle("Add Player")
            .setView(dialogView)
            .setMessage(messageText)
            .setPositiveButton("Add") { _, _ ->
                val label = labelEdit.text.toString()
                val host = hostEdit.text.toString()
                val port = portEdit.text.toString().toIntOrNull() ?: 11000
                
                if (label.isNotEmpty() && host.isNotEmpty()) {
                    // Test connection before adding
                    testPlayerConnection(label, host, port, detectedNetwork?.id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun testPlayerConnection(label: String, host: String, port: Int, networkId: String?) {
        val progressDialog = AlertDialog.Builder(this)
            .setMessage("Testing connection to $host:$port...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val testPlayer = Player(
                    id = UUID.randomUUID().toString(),
                    label = label,
                    host = host,
                    port = port,
                    networkId = networkId
                )
                
                // Try to get player status
                val status = withContext(Dispatchers.IO) {
                    getPlayerStatus(testPlayer)
                }
                
                progressDialog.dismiss()
                
                if (status != null) {
                    // Connection successful - add player
                    android.util.Log.d("SettingsActivity", "Adding player: ${testPlayer.label} to networkId=${testPlayer.networkId}")
                    ConfigManager.addPlayer(this@SettingsActivity, testPlayer)
                    
                    // If player was added to a specific network, switch to that network filter
                    if (testPlayer.networkId != null) {
                        android.util.Log.d("SettingsActivity", "Switching currentNetworkFilter to ${testPlayer.networkId}")
                        currentNetworkFilter = testPlayer.networkId
                    }
                    
                    setupNetworkSelector() // Refresh network selector (will preserve currentNetworkFilter)
                    setupPlayers() // Refresh player list
                    
                    // Sync presets from the new player
                    withContext(Dispatchers.IO) {
                        PresetSyncService.syncPlayersPresets(this@SettingsActivity, listOf(testPlayer))
                    }
                    
                    BlueshiftWidget.refreshWidgets(this@SettingsActivity)
                    
                    Toast.makeText(
                        this@SettingsActivity,
                        "Player added and presets synced",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Connection failed
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Connection Failed")
                        .setMessage("Could not connect to player at $host:$port.\n\nPlease check:\n• Host/IP address is correct\n• Port is correct (default: 11000)\n• Player is powered on\n• Device is on the same network")
                        .setPositiveButton("Add Anyway") { _, _ ->
                            ConfigManager.addPlayer(this@SettingsActivity, testPlayer)
                            
                            // If player was added to a specific network, switch to that network filter
                            if (testPlayer.networkId != null) {
                                currentNetworkFilter = testPlayer.networkId
                            }
                            
                            setupNetworkSelector()
                            setupPlayers()
                            
                            Toast.makeText(
                                this@SettingsActivity,
                                "Player added",
                                Toast.LENGTH_SHORT
                            ).show()
                            
                            // Try to sync presets even if connection test failed
                            CoroutineScope(Dispatchers.Main).launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        PresetSyncService.syncPlayersPresets(this@SettingsActivity, listOf(testPlayer))
                                    }
                                    BlueshiftWidget.refreshWidgets(this@SettingsActivity)
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        "Presets synced successfully",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        "Could not sync presets: player unreachable",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Connection Error")
                    .setMessage("Error testing connection: ${e.message}\n\nDo you want to add the player anyway?")
                    .setPositiveButton("Add Anyway") { _, _ ->
                        val player = Player(
                            id = UUID.randomUUID().toString(),
                            label = label,
                            host = host,
                            port = port,
                            networkId = networkId
                        )
                        ConfigManager.addPlayer(this@SettingsActivity, player)
                        setupNetworkSelector()
                        setupPlayers()
                        
                        Toast.makeText(
                            this@SettingsActivity,
                            "Player added",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        // Try to sync presets even if connection test failed
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    PresetSyncService.syncPlayersPresets(this@SettingsActivity, listOf(player))
                                }
                                BlueshiftWidget.refreshWidgets(this@SettingsActivity)
                                Toast.makeText(
                                    this@SettingsActivity,
                                    "Presets synced successfully",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    "Could not sync presets: player unreachable",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    
    /**
     * Request location permission for SSID detection
     */
    private fun requestLocationPermissionIfNeeded(onGranted: () -> Unit, onDenied: () -> Unit) {
        if (NetworkDetector.hasLocationPermission(this)) {
            onGranted()
            return
        }
        
        // Show explanation first
        AlertDialog.Builder(this)
            .setTitle("Location Permission Needed")
            .setMessage(
                "To automatically detect your WiFi network name, we need location permission. " +
                "This is an Android requirement for reading WiFi SSID.\n\n" +
                "Your location is NOT tracked or stored."
            )
            .setPositiveButton("Grant Permission") { _, _ ->
                requestPermissions(
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_LOCATION_PERMISSION
                )
                // Store callbacks for later
                pendingPermissionCallbacks = Pair(onGranted, onDenied)
            }
            .setNegativeButton("Skip") { _, _ ->
                onDenied()
            }
            .show()
    }
    
    private var pendingPermissionCallbacks: Pair<() -> Unit, () -> Unit>? = null
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            val callbacks = pendingPermissionCallbacks
            pendingPermissionCallbacks = null
            
            if (grantResults.isNotEmpty() && 
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted. Network detection enabled.", Toast.LENGTH_SHORT).show()
                callbacks?.first?.invoke()
            } else {
                Toast.makeText(
                    this, 
                    "Permission denied. Players will work on all networks.", 
                    Toast.LENGTH_LONG
                ).show()
                callbacks?.second?.invoke()
            }
        }
    }
    
    private fun showPlayerOptionsDialog(player: Player) {
        AlertDialog.Builder(this)
            .setTitle(player.label)
            .setItems(arrayOf("Rename", "Delete", "Export presets to M3U")) { _, which ->
                when (which) {
                    0 -> showRenamePlayerDialog(player)
                    1 -> {
                        ConfigManager.deletePlayer(this, player.id)
                        setupPlayers()
                        BlueshiftWidget.refreshWidgets(this)
                    }
                    2 -> exportPresetsToM3U()
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
        // Get players for current network
        val players = if (currentNetworkFilter != null) {
            ConfigManager.getPlayersForNetwork(this, currentNetworkFilter!!)
        } else {
            ConfigManager.getPlayers(this) // All players if "All Networks" selected
        }
        
        if (players.isEmpty()) {
            Toast.makeText(
                this,
                "No players to sync",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        val progressDialog = AlertDialog.Builder(this)
            .setMessage("Syncing BluOS presets...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        manualSyncButton.isEnabled = false
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    PresetSyncService.syncPlayersPresets(this@SettingsActivity, players)
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
        val progressDialog = AlertDialog.Builder(this)
            .setMessage("Discovering BluOS players on local network...\nThis may take up to a minute.")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
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
        
        // Check if there are TuneIn presets
        val tuneInPresets = presets.filter { it.url.startsWith("TuneIn:", ignoreCase = true) }
        
        if (tuneInPresets.isNotEmpty()) {
            // Show warning dialog about TuneIn resolution
            AlertDialog.Builder(this)
                .setTitle("TuneIn Presets Detected")
                .setMessage(
                    "Found ${tuneInPresets.size} TuneIn preset(s).\n\n" +
                    "The app will attempt to resolve these to actual stream URLs using your BluOS player.\n\n" +
                    "Note: This feature is for personal backup/archival purposes only."
                )
                .setPositiveButton("Continue") { _, _ ->
                    performExport(presets)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            performExport(presets)
        }
    }
    
    private fun performExport(presets: List<BluOSPreset>) {
        val progressDialog = AlertDialog.Builder(this)
            .setMessage("Exporting presets...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resolvedPresets = withContext(Dispatchers.IO) {
                    presets.map { preset ->
                        if (preset.url.startsWith("TuneIn:", ignoreCase = true)) {
                            resolveTuneInPreset(preset)
                        } else {
                            preset
                        }
                    }
                }

                // Filter out presets that should be ignored entirely (not exported)
                val ignoredPresets = mutableListOf<BluOSPreset>()
                val exportablePresets = resolvedPresets.filter { preset ->
                    val url = preset.url
                    val shouldIgnore = url.startsWith("Capture:", ignoreCase = true) ||
                        url.startsWith("RadioParadise:", ignoreCase = true)
                    if (shouldIgnore) {
                        ignoredPresets.add(preset)
                    }
                    !shouldIgnore
                }

                // Presets that still have a TuneIn URL after resolution are treated as skipped
                val skippedPresets = exportablePresets.filter { it.url.startsWith("TuneIn:", ignoreCase = true) }
                val exportedPresets = exportablePresets.filterNot { it.url.startsWith("TuneIn:", ignoreCase = true) }

                val skippedCount = skippedPresets.size
                val exportedCount = exportedPresets.size

                val m3uContent = buildString {
                    appendLine("#EXTM3U")
                    appendLine("# Exported from Blueshift Widget")
                    if (skippedCount > 0) {
                        appendLine("# Note: $skippedCount TuneIn preset(s) could not be resolved")
                    }
                    if (ignoredPresets.isNotEmpty()) {
                        appendLine("# Note: ${ignoredPresets.size} preset(s) ignored (Capture:/RadioParadise:)")
                    }
                    appendLine()

                    // List details about skipped and ignored presets for easier debugging
                    if (skippedPresets.isNotEmpty() || ignoredPresets.isNotEmpty()) {
                        appendLine("# --- Skipped / Ignored presets ---")
                        skippedPresets.forEach { preset ->
                            appendLine("# SKIPPED (TuneIn unresolved): ${preset.name} -> ${preset.url}")
                        }
                        ignoredPresets.forEach { preset ->
                            appendLine("# IGNORED (non-exportable scheme): ${preset.name} -> ${preset.url}")
                        }
                        appendLine("# --- End of skipped / ignored list ---")
                        appendLine()
                    }

                    exportedPresets.forEach { preset ->
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
                    this@SettingsActivity,
                    "${applicationContext.packageName}.fileprovider",
                    m3uFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/x-mpegurl"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                progressDialog.dismiss()
                startActivity(Intent.createChooser(shareIntent, "Export M3U Playlist"))
                
                val message = buildString {
                    append("Exported $exportedCount preset(s)")
                    if (skippedCount > 0) {
                        append(", skipped $skippedCount TuneIn preset(s)")
                    }
                    if (ignoredPresets.isNotEmpty()) {
                        append(", ignored ${ignoredPresets.size} preset(s)")
                    }
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(this@SettingsActivity, "Error exporting presets: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private suspend fun resolveTuneInPreset(preset: BluOSPreset): BluOSPreset {
        try {
            // Extract TuneIn ID from URL (e.g., "TuneIn:s2591" -> "s2591")
            val rawTuneInId = preset.url.substringAfter("TuneIn:", "")
            if (rawTuneInId.isEmpty()) return preset

            // Some manually added TuneIn presets use a percent-encoded direct stream URL
            // as the "ID", e.g. "http%3A%2F%2Fradio-metadata.fr%3A8000%2Ffip".
            // In that case, skip the TuneIn API lookup entirely and just decode + use it.
            if (rawTuneInId.startsWith("http%3A", ignoreCase = true) ||
                rawTuneInId.startsWith("https%3A", ignoreCase = true)) {
                val decoded = java.net.URLDecoder.decode(rawTuneInId, "UTF-8")
                return if (decoded.startsWith("http", ignoreCase = true)) {
                    preset.copy(url = decoded)
                } else {
                    preset
                }
            }

            // Some presets prepend the id and then concatenate an actual TuneIn URL, e.g.:
            //   "s25510/http://opml.radiotime.com/Tune.ashx?id=s25510&..."
            // In that case, use only the part before the "/" as the TuneIn id.
            val tuneInId = rawTuneInId.substringBefore("/")
            if (tuneInId.isEmpty()) return preset
            
            // Get current player
            val player = ConfigManager.getSelectedPlayer(this) ?: return preset
            
            // Query BluOS player's RadioBrowse to get TuneIn credentials
            val radioBrowseUrl = "${player.url}/RadioBrowse?service=TuneIn"
            val browseResponse = withContext(Dispatchers.IO) {
                val url = URL(radioBrowseUrl)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }

            // Extract partner ID and serial from the encoded URL in the XML response
            // Example: URL="https%3A%2F%2Fapi.radiotime.com%2F...serial%3D<serial>%26partnerId%3D<partnerId>..."
            val urlAttrRegex = """URL="([^"]+)""".toRegex()
            val encodedUrl = urlAttrRegex.find(browseResponse)?.groupValues?.get(1)
            val decodedUrl = encodedUrl
                ?.replace("%3A", ":")
                ?.replace("%2F", "/")
                ?.replace("%3F", "?")
                ?.replace("%3D", "=")
                ?.replace("%26", "&")
                ?: ""

            val partnerIdRegex = """partnerId=([^&"]+)""".toRegex()
            val serialRegex = """serial=([^&"]+)""".toRegex()
            
            val partnerId = partnerIdRegex.find(decodedUrl)?.groupValues?.get(1)
            val serial = serialRegex.find(decodedUrl)?.groupValues?.get(1)
            
            if (partnerId == null) return preset
            
            // Query TuneIn API with BluOS credentials
            val tuneInUrl = buildString {
                append("http://opml.radiotime.com/Tune.ashx?id=$tuneInId")
                append("&partnerId=$partnerId")
                if (serial != null) {
                    append("&serial=$serial")
                }
                append("&formats=mp3,aac,ogg")
            }
            
            val tuneInResponse = withContext(Dispatchers.IO) {
                val url = URL(tuneInUrl)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
            
            // Parse all stream URLs from response. Depending on the endpoint/settings, this
            // can be either XML with <outline ... URL="..."> tags or a simple newline-
            // separated list of URLs (e.g. multiple .pls/.m3u entries).
            val streamUrls: List<String> = run {
                val xmlUrlRegex = """<outline[^>]+URL="([^"]+)"[^>]*type="audio""".toRegex()
                val xmlMatches = xmlUrlRegex.findAll(tuneInResponse)
                    .map { it.groupValues[1].replace("&amp;", "&") }
                    .toList()

                if (xmlMatches.isNotEmpty()) {
                    xmlMatches
                } else {
                    tuneInResponse
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.startsWith("http", ignoreCase = true) }
                        .toList()
                }
            }
            
            if (streamUrls.isEmpty()) return preset
            
            // Select highest quality stream (look for highest bitrate number in URL)
            val bestUrl = streamUrls.maxByOrNull { url ->
                // Extract bitrate from URL (e.g., "groovesalad130.pls" -> 130)
                val bitrateRegex = """(\d+)\.(pls|m3u|mp3|aac)""".toRegex()
                bitrateRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } ?: streamUrls.first()
            
            // Resolve PLS/M3U files to actual stream URLs
            val finalUrl = if (bestUrl.endsWith(".pls", ignoreCase = true) || 
                              bestUrl.endsWith(".m3u", ignoreCase = true)) {
                resolvePlaylistUrl(bestUrl)
            } else {
                bestUrl
            }
            
            return if (finalUrl != null && finalUrl.startsWith("http", ignoreCase = true)) {
                preset.copy(url = finalUrl)
            } else {
                preset // Keep original if resolution failed
            }
            
        } catch (e: Exception) {
            // Return original preset if resolution fails
            return preset
        }
    }
    
    private suspend fun resolvePlaylistUrl(playlistUrl: String): String? {
        return try {
            withContext(Dispatchers.IO) {           
                val url = URL(playlistUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                try {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }
                    
                    // Parse PLS format: File1=http://...
                    if (playlistUrl.endsWith(".pls", ignoreCase = true)) {
                        val fileRegex = """File\d+=(.+)""".toRegex()
                        fileRegex.find(content)?.groupValues?.get(1)?.trim()
                    } 
                    // Parse M3U format: lines starting with http
                    else {
                        content.lines()
                            .firstOrNull { it.trim().startsWith("http", ignoreCase = true) }
                            ?.trim()
                    }
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun setupAboutSection() {
        // Set app version dynamically from PackageManager
        val versionText = findViewById<TextView>(R.id.app_version)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = "Blueshift v${packageInfo.versionName}"
        } catch (e: Exception) {
            versionText.text = "Blueshift"
        }
        
        // Make source link clickable
        val sourceLink = findViewById<TextView>(R.id.source_link)
        sourceLink.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/arbakker/blueshift"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
            }
        }
    }
}