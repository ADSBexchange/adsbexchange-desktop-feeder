package com.adsbexchange.feeder.data

/** Settings > Appearance: dark (the app's own look), light, or whichever Windows has apps set to. */
enum class Appearance(val key: String, val label: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light"),
    WINDOWS("windows", "Match Windows");

    companion object {
        /** From settings.json; anything else, or nothing, is [DARK]. */
        fun ofKey(key: String?): Appearance = entries.firstOrNull { it.key == key } ?: DARK
    }
}
