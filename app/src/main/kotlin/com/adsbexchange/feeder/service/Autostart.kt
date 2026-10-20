package com.adsbexchange.feeder.service

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * Starting with Windows: a value under the current user's Run key that starts the installed app
 * with `--background` (tray only) at sign-in. Per user, so it needs no admin rights.
 */
object Autostart {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    const val VALUE_NAME = "ADS-B Exchange Feeder"

    /**
     * The installed app's launcher. jpackage's launcher sets jpackage.app-path; it's absent when
     * running from Gradle, so development runs never register themselves.
     */
    fun launcher(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    /** Makes the Run entry match [enabled] for the installed app. False if not installed or it failed. */
    fun apply(enabled: Boolean): Boolean = runCatching {
        val exe = launcher() ?: return false
        val hkcu = WinReg.HKEY_CURRENT_USER
        if (enabled) {
            val command = "\"$exe\" --background"
            val current = if (Advapi32Util.registryValueExists(hkcu, RUN_KEY, VALUE_NAME))
                Advapi32Util.registryGetStringValue(hkcu, RUN_KEY, VALUE_NAME) else null
            if (current != command) Advapi32Util.registrySetStringValue(hkcu, RUN_KEY, VALUE_NAME, command)
        } else if (Advapi32Util.registryValueExists(hkcu, RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(hkcu, RUN_KEY, VALUE_NAME)
        }
        true
    }.getOrDefault(false)
}
