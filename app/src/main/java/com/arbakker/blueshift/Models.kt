package com.arbakker.blueshift

data class Player(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 11000
) {
    val url: String
        get() = "http://$host:$port"
}

data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val isDefault: Boolean = false
)

data class BluOSPreset(
    val id: String,
    val remoteId: String = "",
    val name: String,
    val url: String,
    val image: String?,
    val playerId: String // Which player this preset belongs to
)

data class PlayerStatus(
    val state: String, // "play", "pause", "stop", "stream"
    val title1: String?,
    val title2: String?,
    val title3: String?,
    val artist: String?,
    val album: String?
)
