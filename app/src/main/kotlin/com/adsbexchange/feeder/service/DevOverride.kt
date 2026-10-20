package com.adsbexchange.feeder.service

/**
 * A system property that points the app at a development build of one of its programs
 * (`feeder.readsb`, `feeder.rtlsdrDriver`), as Gradle's run task sets. The installed app ignores
 * them, as it does FEEDER_HOME: its launcher sets `jpackage.app-path`. Otherwise a Java option
 * (JAVA_TOOL_OPTIONS, which any program the user runs can set) could swap the program behind the
 * driver setup's permission prompt for another.
 */
internal fun developmentOverride(name: String, property: (String) -> String? = System::getProperty): String? =
    if (property("jpackage.app-path") != null) null else property(name)?.takeIf { it.isNotBlank() }
