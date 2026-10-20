import java.net.URI
import java.security.MessageDigest


plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

// The app's version, in the MSI and the launcher.
val appVersion = "0.5.20"

// The commit this build came from, shown under Settings > About and in the log so an installed
// build can be matched to its source; "-dirty" if tracked files had uncommitted changes, "unknown"
// when built without git. (As the phone feeder's build.)
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
    }.standardOutput.asText.get().trim()
}.getOrNull()
val gitCommit = git("rev-parse", "--short=7", "HEAD")?.ifEmpty { null }?.let { sha ->
    if (git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) sha else "$sha-dirty"
} ?: "unknown"

// Where users get this app's source, as the GPL requires (Settings > About and the licences screen).
val sourceUrl = "https://github.com/ADSBexchange/adsbexchange-desktop-feeder"

// Where the app looks for new versions (UpdateChecker): this GitHub repository's latest release.
// It must be public, since the app holds no credentials; while it's private, the check quietly
// finds nothing.
val releasesRepo = "ADSBexchange/adsbexchange-desktop-feeder"

// Shows "Beta" in the app (the header, window title, tray tooltip and About) until the full
// release: set to false then.
val betaRelease = true

// Read at run time by com.adsbexchange.feeder.BuildInfo.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/buildInfo")
    inputs.property("version", appVersion)
    inputs.property("commit", gitCommit)
    inputs.property("source", sourceUrl)
    inputs.property("releases", releasesRepo)
    inputs.property("beta", betaRelease)
    outputs.dir(out)
    doLast {
        out.get().file("build-info.properties").asFile.apply { parentFile.mkdirs() }
            .writeText("version=$appVersion\ncommit=$gitCommit\nsource=$sourceUrl\nreleases=$releasesRepo\nbeta=$betaRelease\n")
    }
}
sourceSets.main { resources.srcDir(generateBuildInfo) }

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Windows x64 only (see README).
    implementation(libs.compose.desktop.windows.x64)
    implementation(libs.compose.material3)
    implementation(libs.compose.resources)
    implementation(libs.org.json)
    // Windows APIs: TCP connection owners (gpsd access check), Job Objects, keeping the PC awake.
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(libs.junit)
}

// readsb.exe and rtlsdr-driver.exe are built separately (tools/build-native.ps1, in Docker) and
// copied in as app resources, so the packaged app ships them next to the JVM. Run with
// -PskipNative to build without them (the app then says readsb is missing).
val nativeBuild = rootProject.layout.projectDirectory.dir("native/build")
val readsbExe = nativeBuild.file("readsb.exe")
val driverExe = nativeBuild.file("rtlsdr-driver.exe")
val copyNative = tasks.register<Copy>("copyNative") {
    from(readsbExe, driverExe)
    into(layout.buildDirectory.dir("appResources/windows"))
    onlyIf { !project.hasProperty("skipNative") }
    doFirst {
        listOf(readsbExe, driverExe).forEach {
            check(it.asFile.exists()) { "${it.asFile} is missing: run tools\\build-native.ps1 first" }
        }
    }
}

compose.resources {
    packageOfResClass = "com.adsbexchange.feeder.resources"
    publicResClass = false
}

// Java settings for a small, steady footprint: the app runs all day in the background, and Java's
// defaults are sized for servers (on a 16 GB PC: a 256 MB heap from the start, up to 4 GB, and a
// collector that rarely hands memory back). The heap in use is about 35 MB.
// - Serial collector: one thread, and it gives memory back to Windows after a collection
//   (MaxHeapFreeRatio); Main.kt runs one when the window closes to the tray.
// - 256 MB cap, starting at 32 MB.
// - C1 compiler only: the app is mostly idle, so C2's extra speed isn't worth its memory.
// - Software rendering for the window: about 50 MB less than Direct3D for a mostly still status
//   window, at much the same CPU, and no dependence on the PC's graphics driver.
// Also used by runPreview, so the preview measures what ships. (0.5.6; see the "Desktop resources"
// card on Status.)
val appJvmArgs = listOf(
    "-XX:+UseSerialGC", "-Xms32m", "-Xmx256m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
    "-XX:TieredStopAtLevel=1", "-Dskiko.renderApi=SOFTWARE",
)

compose.desktop {
    application {
        mainClass = "com.adsbexchange.feeder.MainKt"
        jvmArgs += appJvmArgs
        // Compose builds the app folder (createDistributable: launcher, Java runtime, jars, readsb
        // and the driver helper); the MSI around it is ours, from installer/ (see buildMsi below).
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            packageName = "ADS-B Exchange Feeder"
            packageVersion = appVersion
            description = "Feeds ADS-B Exchange from an RTL-SDR stick"
            vendor = "ADS-B Exchange"
            // From :app:suggestRuntimeModules (jdk.unsupported is for JNA), plus java.net.http for the
            // stats uploads (StatsUploader; jdk.crypto.ec, which its TLS needs, is in Compose's defaults).
            modules("java.instrument", "jdk.unsupported", "java.net.http")
            windows {
                // The launcher's icon. tools/icon/MakeIcon.java draws it from the app's icon.svg.
                iconFile.set(project.file("icons/icon.ico"))
            }
        }
    }
}

// Where the app finds the native programs when run from Gradle. Only on the run task: anything in
// application.jvmArgs is also baked into the installed app's launcher, which finds them through
// compose.application.resources.dir instead.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    systemProperty("feeder.readsb", readsbExe.asFile.absolutePath)
    systemProperty("feeder.rtlsdrDriver", driverExe.asFile.absolutePath)
}

// Development only: the real window on a synthetic capture, with local stand-ins for ADS-B Exchange
// (app/src/test/kotlin/.../Preview.kt).
tasks.register<JavaExec>("runPreview") {
    group = "verification"
    description = "Runs the app's window on a synthetic capture, feeding local stand-ins only"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.adsbexchange.feeder.PreviewKt"
    jvmArgs(appJvmArgs)
    systemProperty("feeder.readsb", readsbExe.asFile.absolutePath)
    systemProperty("feeder.repo", rootDir.absolutePath)
    systemProperty("feeder.capture", (findProperty("capture") as String?)
        ?: rootProject.file("tools/testdata/preview.iq").absolutePath)
    // -PpreviewRelease=0.6.0: a stand-in GitHub on 127.0.0.1 offers that release, to see the update notice.
    (findProperty("previewRelease") as String?)?.let { systemProperty("feeder.previewRelease", it) }
}

tasks.test {
    // FeederEngineTest and SdrDriverTest run the real native programs (skipped without them).
    systemProperty("feeder.readsb", readsbExe.asFile.absolutePath)
    systemProperty("feeder.rtlsdrDriver", driverExe.asFile.absolutePath)
    systemProperty("feeder.testdata", rootProject.file("tools/testdata").absolutePath)
}

// --- The installer (installer/Product.wxs) ---------------------------------------------------
// A WiX 3 MSI built from Compose's app folder (createDistributable): branded dialogs, "Open ADS-B
// Exchange Feeder" at the end, and an uninstall that removes the start-with-Windows entry. Compose's
// own MSI (packageMsi) can do none of that. Output: <repo>\Releases (git-ignored: too big for the repo).

// WiX 3.14.1, the last WiX 3 release (it carries the fixes for WiX 3's CVEs), pinned by SHA-256.
// GitHub publishes no digest for this file and most of its programs aren't code-signed, so the hash
// was taken on download (2026-10-15), when all 50 programs and libraries matched, byte for byte,
// those in the WiX 3.14.1 package on nuget.org.
val wixUrl = "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip"
val wixSha256 = "6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31"
val wixDir = rootProject.layout.buildDirectory.dir("wix-3.14.1")
val wixToolset = tasks.register("wixToolset") {
    outputs.dir(wixDir)
    doLast {
        val zip = temporaryDir.resolve("wix314-binaries.zip")
        URI(wixUrl).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == wixSha256) { "WiX download doesn't match its pinned SHA-256 (got $digest)" }
        project.copy { from(project.zipTree(zip)); into(wixDir) }
    }
}

val appImage = layout.buildDirectory.dir("compose/binaries/main/app/ADS-B Exchange Feeder")
val wixWork = layout.buildDirectory.dir("wix")
val installerSrc = rootProject.layout.projectDirectory.dir("installer")

// The GPL as the RTF that WiX's licence page shows.
val licenseRtf = tasks.register("licenseRtf") {
    val source = rootProject.file("LICENSE")
    val target = wixWork.map { it.file("License.rtf") }
    inputs.file(source)
    // Bump when the conversion below changes (Gradle doesn't see edits to a doLast block).
    inputs.property("format", 2)
    outputs.file(target)
    doLast {
        // The licence file is hard-wrapped at 70-odd columns; the installer's box is narrower, so
        // rejoin each paragraph and let the box wrap it. Raw strings: RTF is all backslashes.
        fun rtf(s: String) = s.replace("""\""", """\\""").replace("{", """\{""").replace("}", """\}""")
        val paragraphs = source.readText().replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { p -> p.lines().joinToString(" ") { it.trim() }.trim() }
            .filter { it.isNotEmpty() }
        val par = """\par""" + "\n"
        val body = paragraphs.joinToString(par + par) { rtf(it) }
        val header = """{\rtf1\ansi\deff0{\fonttbl{\f0 Segoe UI;}}\f0\fs16""" + "\n"
        target.get().asFile.writeText(header + body + par + "}\n")
    }
}

// Code signing for releases, through Azure Artifact Signing, on request: -Psign. Needs the
// Artifact Signing client tools (signtool and its plugin), an `az login` by someone with signing
// rights, and the signing account's metadata.json (the account, endpoint and certificate profile).
// See the README's "Building".
val signRelease = providers.gradleProperty("sign").isPresent
val signtool = providers.gradleProperty("signtool")
    .orElse("C:\\Program Files (x86)\\Windows Kits\\10\\bin\\10.0.26100.0\\x64\\signtool.exe")
val signingDlib = providers.gradleProperty("signingDlib")
    .orElse(providers.environmentVariable("LOCALAPPDATA").map { "$it\\Microsoft\\MicrosoftArtifactSigningClientTools\\Azure.CodeSigning.Dlib.dll" })
val signingMetadata = providers.gradleProperty("signingMetadata").orElse("C:\\ArtifactSigning\\metadata.json")

/** Signs [files] (SHA-256, timestamped: the certificate lasts three days, the timestamp keeps them valid), then verifies each. */
fun signAndVerify(files: List<File>) {
    files.forEach { check(it.isFile) { "nothing to sign at $it" } }
    // jpackage makes the launcher read-only, and signing writes the signature into the file.
    files.forEach { it.setWritable(true) }
    fun run(vararg args: String) {
        val pb = ProcessBuilder(signtool.get(), *args).redirectErrorStream(true)
        // The signing plugin signs in through Azure CLI, which a fresh install puts on the PATH only for new shells.
        val azCli = File("C:\\Program Files\\Microsoft SDKs\\Azure\\CLI2\\wbin")
        if (azCli.isDirectory) pb.environment().merge("Path", azCli.path) { old, cli -> "$cli;$old" }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "signtool ${args.first()} failed:\n$out" }
    }
    run("sign", "/fd", "SHA256", "/tr", "http://timestamp.acs.microsoft.com", "/td", "SHA256",
        "/dlib", signingDlib.get(), "/dmdf", signingMetadata.get(), *files.map { it.absolutePath }.toTypedArray())
    run("verify", "/pa", *files.map { it.absolutePath }.toTypedArray())
    logger.lifecycle("Signed and verified: ${files.joinToString { it.name }}")
}

// Our own programs in the app folder, before the MSI is made from it: the launcher, readsb, and the
// driver helper (so Windows' permission prompt names the publisher). The Java runtime and the libraries
// keep their makers' signatures.
val signApp = tasks.register("signApp") {
    dependsOn("createDistributable")
    onlyIf { signRelease }
    doLast {
        val dir = appImage.get().asFile
        signAndVerify(listOf(
            File(dir, "ADS-B Exchange Feeder.exe"),
            File(dir, "app/resources/readsb.exe"),
            File(dir, "app/resources/rtlsdr-driver.exe"),
        ))
    }
}

// Every file in the app folder as WiX components (ids and GUIDs derived from the paths).
val harvestApp = tasks.register<Exec>("harvestApp") {
    dependsOn(wixToolset, "createDistributable", signApp)
    inputs.dir(appImage)
    outputs.file(wixWork.map { it.file("AppFiles.wxs") })
    executable = wixDir.get().file("heat.exe").asFile.absolutePath
    args("dir", appImage.get().asFile.absolutePath, "-nologo", "-cg", "AppFiles", "-dr", "INSTALLDIR",
        "-srd", "-ag", "-sfrag", "-scom", "-sreg", "-var", "var.AppDir",
        "-out", wixWork.get().file("AppFiles.wxs").asFile.absolutePath)
}

val compileInstaller = tasks.register<Exec>("compileInstaller") {
    dependsOn(harvestApp, licenseRtf)
    inputs.dir(installerSrc)
    inputs.file(wixWork.map { it.file("AppFiles.wxs") })
    outputs.dir(wixWork.map { it.dir("obj") })
    executable = wixDir.get().file("candle.exe").asFile.absolutePath
    args("-nologo", "-arch", "x64", "-ext", "WixUIExtension", "-ext", "WixUtilExtension",
        "-dVersion=$appVersion",
        "-dAppDir=${appImage.get().asFile.absolutePath}",
        "-dArt=${installerSrc.dir("art").asFile.absolutePath}",
        "-dLicenseRtf=${wixWork.get().file("License.rtf").asFile.absolutePath}",
        "-dIconFile=${project.file("icons/icon.ico").absolutePath}",
        installerSrc.file("Product.wxs").asFile.absolutePath,
        wixWork.get().file("AppFiles.wxs").asFile.absolutePath,
        "-out", wixWork.get().dir("obj").asFile.absolutePath + "\\")
}

val msiName = "ADS-B Exchange Feeder-$appVersion.msi"
val linkInstaller = tasks.register<Exec>("linkInstaller") {
    dependsOn(compileInstaller)
    inputs.dir(wixWork.map { it.dir("obj") })
    // light reads the files themselves (the app's, the artwork, the licence) when it builds the cab.
    inputs.dir(appImage)
    inputs.dir(installerSrc)
    inputs.file(wixWork.map { it.file("License.rtf") })
    inputs.file(project.file("icons/icon.ico"))
    outputs.file(wixWork.map { it.file(msiName) })
    executable = wixDir.get().file("light.exe").asFile.absolutePath
    // Per machine since 0.5.16. ICE38/43/57 judge the shortcut components as if the Start menu and
    // desktop folders were the user's, but in a per-machine install (ALLUSERS=1) they're the
    // all-users ones, keyed under HKLM: a known false positive for per-machine shortcuts.
    args("-nologo", "-ext", "WixUIExtension", "-ext", "WixUtilExtension", "-cultures:en-us",
        "-sice:ICE38", "-sice:ICE43", "-sice:ICE57", "-spdb",
        wixWork.get().file("obj/Product.wixobj").asFile.absolutePath,
        wixWork.get().file("obj/AppFiles.wixobj").asFile.absolutePath,
        "-out", wixWork.get().file(msiName).asFile.absolutePath)
}

// The MSI last, once everything inside it is signed.
val signMsi = tasks.register("signMsi") {
    dependsOn(linkInstaller)
    onlyIf { signRelease }
    doLast { signAndVerify(listOf(wixWork.get().file(msiName).asFile)) }
}

/** The installer: .\gradlew.bat :app:buildMsi (after tools\build-native.ps1). */
tasks.register<Copy>("buildMsi") {
    group = "distribution"
    description = "Builds the branded per-machine MSI into the Releases folder (signed with -Psign)"
    dependsOn(linkInstaller, signMsi)
    from(wixWork.map { it.file(msiName) })
    into(rootProject.layout.projectDirectory.dir("Releases"))
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(copyNative) }

// Every resolved version, transitive ones included, is recorded in gradle.lockfile. To change a
// dependency: edit gradle/libs.versions.toml, then run the build with --write-locks (see README).
dependencyLocking { lockAllConfigurations() }
