package com.arbakker.blueshift

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Immediately launch settings and close this activity
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
        
        // Sync presets in background if needed (24-hour cache)
        syncPresetsIfNeeded()
    }
    
    private fun syncPresetsIfNeeded() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ConfigManager.needsPresetSync(this@MainActivity)) {
                    PresetSyncService.syncAllPresets(this@MainActivity)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error syncing presets", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Preset sync failed: ${e.message ?: "unknown"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
