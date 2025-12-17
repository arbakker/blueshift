package com.arbakker.blueshift

/**
 * Feature flags for enabling/disabling experimental or optional features
 */
object FeatureFlags {
    /**
     * Enable auto-discovery of BluOS players on the local network.
     * When enabled, shows "Discover Players" button in settings.
     */
    const val ENABLE_PLAYER_DISCOVERY = false
    
    /**
     * Enable export of presets to M3U playlist file.
     * Includes TuneIn resolution for maximum compatibility.
     */
    const val ENABLE_PRESET_EXPORT = true
}
