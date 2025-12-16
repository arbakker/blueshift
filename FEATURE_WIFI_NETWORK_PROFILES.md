# Feature: WiFi Network-Based Player Profiles

## Overview
Enable automatic player configuration switching based on connected WiFi network. Each WiFi network can have its own set of associated players, allowing seamless transitions between different locations (home, office, vacation home, etc.).

## User Story
As a user with BluOS players in multiple locations, I want the widget to automatically show the correct players when I connect to different WiFi networks, so I don't have to manually switch configurations when traveling between locations.

## Feature Description

### Core Functionality
1. **WiFi Network Registration**
   - When adding a player, automatically detect and register the current WiFi SSID
   - Store player-to-network association in app configuration
   - Allow users to manually assign/reassign players to WiFi networks

2. **Automatic Profile Switching**
   - Monitor WiFi connection state changes
   - When connected to WiFi, load players associated with that network
   - Show only relevant players for the current network
   - Automatically refresh widget when network changes

3. **Fallback Behavior**
   - When not connected to WiFi: Show message "Not connected to WiFi network with associated players"
   - When connected to unknown network: Show message with option to "Add players for this network"
   - When connected to network with no players: Show setup prompt

### User Interface Changes

#### Settings Activity
- **Player List**: Show WiFi network name next to each player (e.g., "Living Room (Home WiFi)")
- **Add Player Dialog**: 
  - Automatically populate current WiFi SSID
  - Allow manual network name entry
  - Option to add to "All Networks" (always visible)
- **Network Management Section**:
  - List all registered networks with player count
  - Option to rename networks
  - Option to merge/split network configurations
  - Delete network profiles

#### Widget Display
- **Connected State**: Show players normally with network indicator
- **Disconnected State**: 
  - Show icon with WiFi off symbol
  - Display message: "Not connected to WiFi network with associated players"
  - Tap to open settings
- **Unknown Network State**:
  - Show discover/add player prompt
  - Quick action to assign existing players to this network

## Technical Implementation

### Data Model Changes

```kotlin
data class Player(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 11000,
    val wifiNetworks: List<String> = emptyList() // SSIDs this player is available on
)

data class WiFiNetworkProfile(
    val ssid: String,
    val displayName: String?, // User-friendly name
    val playerIds: List<String>,
    val dateAdded: Long,
    val lastConnected: Long?
)
```

### ConfigManager Updates
```kotlin
// New methods needed
fun getPlayersForNetwork(context: Context, ssid: String): List<Player>
fun getCurrentWiFiSSID(context: Context): String?
fun addPlayerToNetwork(context: Context, playerId: String, ssid: String)
fun removePlayerFromNetwork(context: Context, playerId: String, ssid: String)
fun getNetworkProfiles(context: Context): List<WiFiNetworkProfile>
fun deleteNetworkProfile(context: Context, ssid: String)
```

### WiFi State Monitoring
```kotlin
class WiFiStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                    WifiManager.EXTRA_NETWORK_INFO
                )
                if (networkInfo?.isConnected == true) {
                    handleWiFiConnected(context)
                } else {
                    handleWiFiDisconnected(context)
                }
            }
        }
    }
    
    private fun handleWiFiConnected(context: Context) {
        val ssid = ConfigManager.getCurrentWiFiSSID(context)
        val players = ConfigManager.getPlayersForNetwork(context, ssid ?: "")
        // Update widget to show network-specific players
        BlueshiftWidget.refreshWidgets(context)
    }
    
    private fun handleWiFiDisconnected(context: Context) {
        // Update widget to show disconnected state
        BlueshiftWidget.refreshWidgets(context)
    }
}
```

### Permissions Required
```xml
<!-- In AndroidManifest.xml -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- For Android 10+ to get SSID -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### Widget State Handling
```kotlin
// In BlueshiftWidget.kt
private fun updateWidgetForNetworkState(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
    val views = RemoteViews(context.packageName, R.layout.blueshift_widget)
    
    val currentSSID = ConfigManager.getCurrentWiFiSSID(context)
    val players = if (currentSSID != null) {
        ConfigManager.getPlayersForNetwork(context, currentSSID)
    } else {
        emptyList()
    }
    
    if (players.isEmpty()) {
        // Show disconnected/no players state
        views.setTextViewText(R.id.now_playing, "Not connected to WiFi network with associated players")
        views.setViewVisibility(R.id.preset_list, View.GONE)
        views.setViewVisibility(R.id.wifi_warning, View.VISIBLE)
    } else {
        // Show normal player list
        views.setViewVisibility(R.id.preset_list, View.VISIBLE)
        views.setViewVisibility(R.id.wifi_warning, View.GONE)
    }
    
    appWidgetManager.updateAppWidget(appWidgetId, views)
}
```

## Implementation Phases

### Phase 1: Basic WiFi Detection
- Add WiFi SSID detection when adding players
- Store network association in Player model
- Display network name in settings

### Phase 2: Network Filtering
- Filter players by current WiFi network
- Show all players when network filtering disabled
- Add "All Networks" option for universal players

### Phase 3: Automatic Switching
- Register WiFi state broadcast receiver
- Auto-refresh widget on network change
- Implement disconnected state UI

### Phase 4: Network Management
- Add network profile management UI
- Allow reassigning players to networks
- Network rename/merge/delete functionality

### Phase 5: Polish
- Smooth transitions between network states
- Notification for network changes
- Quick actions for unknown networks

## User Settings

New settings to add:
- **Enable Network Profiles**: Toggle feature on/off
- **Auto-switch on Network Change**: Enable/disable automatic switching
- **Show All Players**: Override network filtering (show all players regardless of network)
- **Remember Unknown Networks**: Auto-create profiles for new networks

## Edge Cases & Considerations

1. **Privacy**: WiFi SSID requires location permission on Android 10+
2. **Mobile Data**: How to handle when on cellular? Show message or last known network?
3. **VPN**: VPN might mask actual WiFi network
4. **Hidden SSIDs**: May return empty string, need fallback
5. **Network Name Changes**: What happens if user renames their WiFi network?
6. **Multiple Players Same Network**: Support multiple homes on same SSID (e.g., "Home")
7. **Roaming**: Handle WiFi network transitions smoothly
8. **Background Refresh**: Respect Android battery optimization

## Testing Scenarios

- [ ] Add player on WiFi network A
- [ ] Switch to WiFi network B, verify message shown
- [ ] Add player on WiFi network B, verify both players in list
- [ ] Switch back to network A, verify only network A players shown
- [ ] Disconnect WiFi, verify disconnected message
- [ ] Connect to mobile data, verify behavior
- [ ] Handle location permission denial gracefully
- [ ] Test with hidden SSID networks
- [ ] Test with VPN active
- [ ] Verify battery impact of WiFi monitoring

## Benefits

- **Seamless Multi-Location Use**: Perfect for vacation homes, office, friends' houses
- **Reduced Manual Configuration**: No need to manually switch players
- **Better User Experience**: Widget "just works" wherever you are
- **Cleaner UI**: Only see relevant players for current location
- **Less Confusion**: No trying to connect to unreachable players

## Alternatives Considered

1. **Manual Profile Switching**: User manually switches between profiles
   - Pro: No location permission needed
   - Con: Extra step, easy to forget

2. **IP Range Detection**: Detect network by IP address range
   - Pro: No location permission needed
   - Con: Less reliable, harder to implement

3. **Player Reachability**: Show only reachable players
   - Pro: Simple, works everywhere
   - Con: Slow startup, doesn't help with organization

## Open Questions

1. Should we support player discovery per network?
2. How to handle players that work on multiple networks (e.g., cloud-connected)?
3. Should we cache player status per network?
4. Do we need a "traveling mode" that disables network checking?
5. Should network profiles sync across devices (if we add cloud sync)?

## Status
**PROPOSED** - Not yet implemented


---

Yes! Android provides several ways to detect WiFi changes. Here are the main approaches:

## 1. **BroadcastReceiver (Legacy - Still Works)**

Register for WiFi-related broadcast actions:

```kotlin
class WiFiStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                    WifiManager.EXTRA_NETWORK_INFO
                )
                if (networkInfo?.isConnected == true) {
                    // WiFi connected
                    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val wifiInfo = wifiManager.connectionInfo
                    val ssid = wifiInfo.ssid.removeSurrounding("\"")
                    // Handle WiFi connected to SSID
                }
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                // WiFi enabled/disabled
            }
        }
    }
}
```

Register in AndroidManifest.xml:
```xml
<receiver android:name=".WiFiStateReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.net.wifi.NETWORK_STATE_CHANGED" />
        <action android:name="android.net.wifi.WIFI_STATE_CHANGED" />
    </intent-filter>
</receiver>
```

## 2. **ConnectivityManager.NetworkCallback (Modern - Recommended)**

This is the newer, more flexible approach (Android 5.0+):

```kotlin
class NetworkMonitor(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Network connected
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
            if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // WiFi connected
                handleWiFiConnected()
            }
        }
        
        override fun onLost(network: Network) {
            // Network disconnected
            handleWiFiDisconnected()
        }
        
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            // Network capabilities changed (e.g., WiFi to mobile)
            val isWiFi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isWiFi) {
                handleWiFiConnected()
            }
        }
    }
    
    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }
    
    fun stopMonitoring() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
```

## 3. **Getting Current SSID**

To get the actual WiFi network name (SSID):

```kotlin
fun getCurrentWiFiSSID(context: Context): String? {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val wifiInfo = wifiManager.connectionInfo
    
    // Android 10+ requires location permission
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            // Need ACCESS_FINE_LOCATION permission
            val ssid = wifiInfo.ssid
            return if (ssid != "<unknown ssid>") {
                ssid.removeSurrounding("\"")
            } else {
                null
            }
        }
    } else {
        // Pre-Android 10
        return wifiInfo.ssid?.removeSurrounding("\"")
    }
    
    return null
}
```

## Key Differences:

- **BroadcastReceiver**: Simpler for basic use cases, works in background
- **NetworkCallback**: More modern, better battery life, more granular control
- **Android 10+ (API 29)**: Requires `ACCESS_FINE_LOCATION` permission to get SSID

## For Your Widget:

I'd recommend using **NetworkCallback** in your app/service, combined with a widget update trigger. You can monitor in a background service or register the callback in your widget's `onUpdate()` method.

Would you like me to implement this for the Blueshift widget?