# Blueshift Widget - User Guide

## Overview
Blueshift is an Android widget for controlling BluOS/Bluesound players and accessing your favorite presets directly from your home screen.

## Features

### Manual Refresh
The widget does not automatically update to preserve battery life. To refresh the widget and see the current playback status:
- **Tap the "Now Playing" area** (the text showing the current song/station)
- This will query the player and update the widget with the latest information

### WiFi Network Detection
The widget uses WiFi network detection to organize players by location:

- **Automatic Network Assignment**: When you add a player, the widget automatically detects your current WiFi network (SSID) and associates the player with that network
- **Network-Aware Display**: The widget only shows players that belong to your currently connected WiFi network
- **Status Messages**: 
  - When not connected to WiFi: "Not connected to WiFi"
  - When connected to WiFi without registered players: "No players for: [Network Name]"
- **Location Permission Required**: On Android 10 and later, the app needs location permission to read WiFi network names. You'll be prompted to grant this when adding your first player.

**Important Note on Multiple Networks with Same SSID**: If you have multiple WiFi networks at different locations using the same SSID (e.g., multiple "Home" networks), the widget will treat them as the same network. To avoid confusion, ensure each location uses a unique WiFi network name.

### Player Cycling in Widget
When you have multiple players registered for a network:

- **Tap the player icon** (left side of the widget header) to cycle through available players
- **Network-Filtered Cycling**: The widget only cycles through players registered for your currently connected WiFi network
- Players not associated with the current network are automatically hidden
- If you're not connected to WiFi or have no players for the current network, player cycling is disabled

### Preset Order
Presets are displayed in the widget's station list according to their ordering:

- **Preset ID Order**: Presets are sorted by their BluOS preset ID (the remote ID returned by the player)
- This matches the order presets appear in the BluOS apps
- **Note**: Presets are NOT sorted alphabetically - they follow the order configured in your BluOS system

### Preset Synchronization
The widget syncs presets from your BluOS player in the following situations:

- **Manual Sync in Settings**: Use the "Sync" button next to the network selector in the Players section to sync presets for the currently selected network
  - If "All Networks" is selected, it syncs presets for all players
  - If a specific network is selected, it only syncs presets for players on that network
- **After Adding a Player**: Presets are automatically fetched when you add a new player
- **Manual Refresh Required**: The widget does not automatically sync presets in the background. If you add or modify presets in the BluOS app, you need to manually sync in Settings to see the changes

## Getting Started

### Initial Setup
1. Long-press on your home screen and add the Blueshift widget
2. Tap the settings button (gear icon) in the widget
3. Grant location permission when prompted (required for WiFi detection)
4. Add your first player by providing:
   - Player name/label
   - IP address or hostname
   - Port (default: 11000)
5. The player will be automatically associated with your current WiFi network
6. Presets will be automatically synced from the player

### Adding Players on Different Networks
1. Connect to the WiFi network where the player is located
2. Open widget settings
3. Add the player - it will be automatically associated with the current WiFi network

### Switching Between Networks
1. Connect to a different WiFi network
2. Tap the "Now Playing" area to refresh the widget
3. The widget will automatically show only players for the newly connected network

## Troubleshooting

**Widget shows "Not connected to WiFi"**
- Ensure WiFi is enabled and you're connected to a network
- Check that location permission is granted

**Widget shows "No players for: [Network Name]"**
- You haven't registered any players for this WiFi network yet
- Tap "Open settings to add players" or use the settings button to add a player

**Presets not showing up**
- Tap the "Sync" button in the Players section of Settings to manually sync presets
- Ensure the correct network is selected if you have multiple networks
- Ensure the player is reachable on the network

**Player not responding**
- Check that the player is powered on and connected to the network
- Verify the IP address/hostname hasn't changed
- Try manually refreshing by tapping the "Now Playing" area

**Players from another location showing up**
- This can happen if both networks use the same WiFi network name (SSID)
- Use unique network names for each location to avoid this issue
