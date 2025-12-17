# Implementation Plan: Simplified Network Profiles

## Overview
Add basic network profile support to automatically associate players with WiFi networks. This implementation stores WiFi SSID when adding players and allows filtering players by network.

## Goals
- ✅ Detect current WiFi SSID when adding players
- ✅ Store network association with each player
- ✅ Show network selector when multiple networks exist
- ✅ Filter player list by selected network
- ❌ NO automatic switching (manual only)
- ⚠️ Requires ACCESS_FINE_LOCATION permission on Android 10+ to read SSID

## Data Model Changes

### Updated Models.kt

```kotlin
data class Player(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 11000,
    val networkId: String? = null  // Reference to NetworkProfile.id
)

data class NetworkProfile(
    val id: String,
    val name: String? = null,  // Optional user-provided name like "Home" or "Office"
    val ssidOrSubnet: String? = null,  // WiFi SSID (e.g., "MyHomeWiFi")
    val isDefault: Boolean = false  // Selected network
)
```

**Migration Strategy:**
- Existing players without `networkId` will show in all networks (backward compatible)
- First network created becomes default automatically

## Implementation Steps

### Phase 1: Core Network Detection (No UI Changes)

#### 1.1 Add Network Detection Utility
**File:** `app/src/main/java/com/arbakker/blueshift/NetworkDetector.kt`

```kotlin
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

object NetworkDetector {
    /**
     * Get current WiFi SSID
     * Returns SSID like "MyHomeWiFi" or null if not connected to WiFi
     * 
     * Note: On Android 10+ (API 29), requires ACCESS_FINE_LOCATION permission
     */
    fun getCurrentWiFiSSID(context: Context): String? {
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
            android.content.pm.PackageManager.PERMISSION_GRANTED
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
```

**Test:** Can detect current WiFi SSID (with proper permissions)

---

#### 1.2 Add Permission Request Handling
**File:** `app/src/main/java/com/arbakker/blueshift/SettingsActivity.kt`

Add permission request constants and handler:

```kotlin
class SettingsActivity : AppCompatActivity() {
    
    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
    }
    
    // ... existing code ...
    
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
            }
            .setNegativeButton("Skip") { _, _ ->
                onDenied()
            }
            .show()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && 
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted. Network detection enabled.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this, 
                    "Permission denied. Players will work on all networks.", 
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
```

**Update AndroidManifest.xml:**

Add location permission:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

**Test:** Permission dialog shows with clear explanation, handles grant/deny correctly

---

### Phase 2: ConfigManager Updates

#### 2.1 Add NetworkProfile Storage
**File:** `app/src/main/java/com/arbakker/blueshift/ConfigManager.kt`

Add these methods:

```kotlin
private const val KEY_NETWORK_PROFILES = "network_profiles"

// Network Profile Management
fun getNetworkProfiles(context: Context): List<NetworkProfile> {
    val json = getPrefs(context).getString(KEY_NETWORK_PROFILES, null) ?: return emptyList()
    val type = object : TypeToken<List<NetworkProfile>>() {}.type
    return try {
        gson.fromJson(json, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

fun saveNetworkProfiles(context: Context, profiles: List<NetworkProfile>) {
    val json = gson.toJson(profiles)
    getPrefs(context).edit().putString(KEY_NETWORK_PROFILES, json).apply()
}

fun getDefaultNetwork(context: Context): NetworkProfile? {
    return getNetworkProfiles(context).firstOrNull { it.isDefault }
}

fun setDefaultNetwork(context: Context, networkId: String) {
    val profiles = getNetworkProfiles(context).map { profile ->
        profile.copy(isDefault = profile.id == networkId)
    }
    saveNetworkProfiles(context, profiles)
}

fun addOrUpdateNetworkProfile(context: Context, profile: NetworkProfile): NetworkProfile {
    val profiles = getNetworkProfiles(context).toMutableList()
    val existingIndex = profiles.indexOfFirst { it.id == profile.id }
    
    val finalProfile = if (existingIndex >= 0) {
        profiles[existingIndex] = profile
        profile
    } else {
        // First network becomes default
        val isFirst = profiles.isEmpty()
        val newProfile = profile.copy(isDefault = isFirst)
        profiles.add(newProfile)
        newProfile
    }
    
    saveNetworkProfiles(context, profiles)
    return finalProfile
}

fun findOrCreateNetworkProfile(context: Context, ssid: String?): NetworkProfile? {
    if (ssid.isNullOrBlank()) return null
    
    // Check if network already exists
    val existing = getNetworkProfiles(context)
        .firstOrNull { it.ssidOrSubnet == ssid }
    
    if (existing != null) return existing
    
    // Create new network profile
    val newProfile = NetworkProfile(
        id = UUID.randomUUID().toString(),
        name = null, // Will be auto-suggested in UI
        ssidOrSubnet = ssid,
        isDefault = getNetworkProfiles(context).isEmpty() // First one is default
    )
    
    return addOrUpdateNetworkProfile(context, newProfile)
}

fun getPlayersForNetwork(context: Context, networkId: String?): List<Player> {
    val allPlayers = getPlayers(context)
    
    // If no network specified, show players without network assignment
    if (networkId == null) {
        return allPlayers.filter { it.networkId == null }
    }
    
    // Show players for this network OR players without network (legacy/universal)
    return allPlayers.filter { it.networkId == networkId || it.networkId == null }
}

fun deleteNetworkProfile(context: Context, networkId: String) {
    val profiles = getNetworkProfiles(context).filter { it.id != networkId }
    
    // If we deleted the default, make first remaining the default
    if (profiles.isNotEmpty() && profiles.none { it.isDefault }) {
        val updated = profiles.mapIndexed { index, profile ->
            if (index == 0) profile.copy(isDefault = true) else profile
        }
        saveNetworkProfiles(context, updated)
    } else {
        saveNetworkProfiles(context, profiles)
    }
    
    // Remove network reference from players
    val players = getPlayers(context).map { player ->
        if (player.networkId == networkId) {
            player.copy(networkId = null)
        } else {
            player
        }
    }
    savePlayers(context, players)
}
```

**Test:** Can create, read, update network profiles

---

### Phase 3: Update Add Player Flow

#### 3.1 Modify Player Data Model
**File:** `app/src/main/java/com/arbakker/blueshift/Models.kt`

```kotlin
data class Player(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 11000,
    val networkId: String? = null
)

data class NetworkProfile(
    val id: String,
    val name: String? = null,
    val ssidOrSubnet: String? = null,
    val isDefault: Boolean = false
)
```

#### 3.2 Update Add Player Dialog
**File:** `app/src/main/java/com/arbakker/blueshift/SettingsActivity.kt`

Modify `showAddPlayerDialog()`:

```kotlin
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
            
            if (label.isNotBlank() && host.isNotBlank()) {
                val player = Player(
                    id = UUID.randomUUID().toString(),
                    label = label,
                    host = host,
                    port = port,
                    networkId = detectedNetwork?.id  // Associate with current network
                )
                
                ConfigManager.addPlayer(this, player)
                setupPlayers()
                BlueshiftWidget.refreshWidgets(this)
                Toast.makeText(this, "Player added", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}
```

**Test:** Adding player automatically detects and assigns network

---

### Phase 4: Network Selector UI

#### 4.1 Add Network Selector to Settings Layout
**File:** `app/src/main/res/layout/activity_settings.xml`

Add below the "Players" section header:

```xml
<!-- Network Selector (shown when multiple networks exist) -->
<LinearLayout
    android:id="@+id/network_selector_container"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:layout_marginBottom="8dp"
    android:visibility="gone">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Network:"
        android:textSize="14sp"
        android:textColor="?android:attr/textColorSecondary" />

    <Spinner
        android:id="@+id/network_selector_spinner"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="12dp"
        android:layout_weight="1" />
</LinearLayout>
```

#### 4.2 Implement Network Selector Logic
**File:** `app/src/main/java/com/arbakker/blueshift/SettingsActivity.kt`

Add to class:

```kotlin
private lateinit var networkSelectorContainer: LinearLayout
private lateinit var networkSelectorSpinner: Spinner
private var currentNetworkFilter: String? = null

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    
    // ... existing initialization ...
    
    networkSelectorContainer = findViewById(R.id.network_selector_container)
    networkSelectorSpinner = findViewById(R.id.network_selector_spinner)
    
    setupNetworkSelector()
    setupPlayers()
    // ... rest of setup ...
}

private fun setupNetworkSelector() {
    val networks = ConfigManager.getNetworkProfiles(this)
    
    // Hide selector if only one or no networks
    if (networks.size <= 1) {
        networkSelectorContainer.visibility = View.GONE
        currentNetworkFilter = networks.firstOrNull()?.id
        return
    }
    
    networkSelectorContainer.visibility = View.VISIBLE
    
    // Create options: "All Networks" + individual networks
    val options = mutableListOf("All Networks")
    options.addAll(networks.map { network ->
        network.name ?: network.ssidOrSubnet ?: "Network ${network.id.take(8)}"
    })
    
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    networkSelectorSpinner.adapter = adapter
    
    // Select default network
    val defaultNetwork = ConfigManager.getDefaultNetwork(this)
    val defaultIndex = if (defaultNetwork != null) {
        networks.indexOf(defaultNetwork) + 1 // +1 for "All Networks"
    } else {
        0
    }
    networkSelectorSpinner.setSelection(defaultIndex)
    currentNetworkFilter = if (defaultIndex == 0) null else networks[defaultIndex - 1].id
    
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
    val players = if (currentNetworkFilter == null) {
        ConfigManager.getPlayers(this) // All players
    } else {
        ConfigManager.getPlayersForNetwork(this, currentNetworkFilter)
    }
    
    val playerNames = players.map { player ->
        val networkIndicator = if (player.networkId != null) {
            val network = ConfigManager.getNetworkProfiles(this)
                .firstOrNull { it.id == player.networkId }
            val networkName = network?.name ?: network?.ssidOrSubnet ?: "Unknown"
            " (${networkName})"
        } else {
            "" // No network (universal player)
        }
        "${player.label} (${player.host}:${player.port})$networkIndicator"
    }
    
    playersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, playerNames.toMutableList())
    playersList.adapter = playersAdapter
    
    playersList.setOnItemClickListener { _, _, position, _ ->
        val player = players[position]
        showPlayerOptionsDialog(player)
    }
}
```

**Test:** 
- With 1 network: Selector hidden, players filtered
- With 2+ networks: Selector visible, can switch between networks
- "All Networks" shows all players

---

### Phase 5: Network Management UI

#### 5.1 Add Network Management Option
**File:** `app/src/main/java/com/arbakker/blueshift/SettingsActivity.kt`

Add button to layout (or menu option):

```kotlin
private fun showNetworkManagementDialog() {
    val networks = ConfigManager.getNetworkProfiles(this)
    
    if (networks.isEmpty()) {
        Toast.makeText(this, "No networks configured", Toast.LENGTH_SHORT).show()
        return
    }
    
    val networkNames = networks.map { network ->
        val name = network.name ?: network.ssidOrSubnet ?: "Unnamed"
        val defaultMarker = if (network.isDefault) " ★" else ""
        val playerCount = ConfigManager.getPlayersForNetwork(this, network.id).size
        "$name ($playerCount players)$defaultMarker"
    }.toTypedArray()
    
    AlertDialog.Builder(this)
        .setTitle("Manage Networks")
        .setItems(networkNames) { _, which ->
            showNetworkOptionsDialog(networks[which])
        }
        .setNegativeButton("Close", null)
        .show()
}

private fun showNetworkOptionsDialog(network: NetworkProfile) {
    val options = arrayOf("Rename Network", "Delete Network")
    
    AlertDialog.Builder(this)
        .setTitle(network.name ?: network.ssidOrSubnet ?: "Network")
        .setItems(options) { _, which ->
            when (which) {
                0 -> showRenameNetworkDialog(network)
                1 -> confirmDeleteNetwork(network)
            }
        }
        .show()
}

private fun showRenameNetworkDialog(network: NetworkProfile) {
    val input = EditText(this).apply {
        setText(network.name ?: NetworkDetector.suggestNetworkName(network.ssidOrSubnet))
        selectAll()
    }
    
    AlertDialog.Builder(this)
        .setTitle("Rename Network")
        .setView(input)
        .setPositiveButton("Rename") { _, _ ->
            val newName = input.text.toString().trim()
            if (newName.isNotBlank()) {
                val updated = network.copy(name = newName)
                ConfigManager.addOrUpdateNetworkProfile(this, updated)
                setupNetworkSelector()
                setupPlayers()
                Toast.makeText(this, "Network renamed", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun confirmDeleteNetwork(network: NetworkProfile) {
    val playerCount = ConfigManager.getPlayersForNetwork(this, network.id)
        .count { it.networkId == network.id }
    
    AlertDialog.Builder(this)
        .setTitle("Delete Network?")
        .setMessage(
            "This will remove the network profile. " +
            "$playerCount player(s) will become universal (visible in all networks)."
        )
        .setPositiveButton("Delete") { _, _ ->
            ConfigManager.deleteNetworkProfile(this, network.id)
            setupNetworkSelector()
            setupPlayers()
            Toast.makeText(this, "Network deleted", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
```

**Test:** Can rename and delete networks

---

## Testing Checklist

### Basic Functionality
- [ ] Add player on WiFi network A - should auto-detect and assign network
- [ ] Add player on WiFi network B - should create new network profile
- [ ] Network selector appears when 2+ networks exist
- [ ] Network selector hidden when 0-1 networks exist
- [ ] Can switch between networks in selector
- [ ] Player list filters correctly by selected network
- [ ] "All Networks" option shows all players

### Network Management
- [ ] Can rename network
- [ ] Can delete network
- [ ] Deleting network removes networkId from affected players
- [ ] Players without networkId show in all filtered views
- [ ] Default network remembered across app restarts

### Edge Cases
- [ ] No WiFi connected: Players added with networkId = null
- [ ] Switching WiFi while app open: Detector returns new subnet
- [ ] Player manually added with IP from different subnet: Works normally
- [ ] All networks deleted: App still functions, no selector shown
- [ ] First network created becomes default automatically

### UI/UX
- [ ] Network names displayed clearly in player list
- [ ] Network detection message shown in add player dialog
- [ ] Toast messages confirm actions
- [ ] Widget refreshes after network/player changes

## Migration & Backward Compatibility

**Existing Data:**
- Existing players have no `networkId` field → treated as `null` → shown in all networks
- No existing network profiles → created automatically when players added
- First-time users → seamless experience, networks auto-detected

**Rollback:**
- If feature disabled, players without `networkId` continue to work
- Network profiles ignored if not needed

## Future Enhancements (Not in This Phase)

- [ ] Automatic network switching based on WiFi changes
- [ ] Cloud-connected players (available on all networks)
- [ ] Network discovery/scan feature
- [ ] Import/export network configurations
- [ ] Per-network preset lists

## Performance Considerations

- Network detection happens only when adding players (not continuous)
- No background monitoring = zero battery impact
- Network filtering done in-memory (fast)
- SSID detection requires location permission on Android 10+

## Privacy & Permissions

⚠️ **Location permission required on Android 10+** - needed to read WiFi SSID
✅ **NO background monitoring** - manual selection only
⚠️ **WiFi SSID stored** - user's network name saved in app preferences

Required permissions:
- `ACCESS_NETWORK_STATE` - to detect WiFi vs mobile (already have)
- `ACCESS_WIFI_STATE` - to get network info (already have)
- `ACCESS_FINE_LOCATION` - to read SSID on Android 10+ (NEW - need to add)

**Permission Handling:**
- Request location permission when user adds first player
- Show explanation: "Needed to detect your WiFi network name"
- If denied: Player added without network association (works on all networks)
- User can manually manage network associations later

## Estimated Effort

- **Phase 1 (Network Detection):** 2 hours
- **Phase 2 (ConfigManager):** 3 hours
- **Phase 3 (Add Player Flow):** 2 hours
- **Phase 4 (Network Selector UI):** 3 hours
- **Phase 5 (Network Management):** 2 hours
- **Testing & Polish:** 2 hours

**Total: ~14 hours**

## Success Criteria

✅ Users can add players and networks are auto-detected
✅ Network selector appears when multiple networks exist
✅ Player list filters by selected network
✅ Can manage (rename/delete) network profiles
✅ No location permission required
✅ Backward compatible with existing data
✅ Zero battery impact (no background monitoring)
