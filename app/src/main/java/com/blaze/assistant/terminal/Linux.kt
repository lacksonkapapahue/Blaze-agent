package com.blaze.assistant.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A real Linux (Alpine) inside Blaze, run with PRoot like UserLAnd/Termux do: no root needed.
 * `apk add python3 nodejs git openjdk17 …` all work. The rootfs (~3.5 MB) is downloaded once
 * into filesDir/linux/alpine; PRoot and its loader ship in the APK as native libs.
 */
object Linux {
    private const val VER = "3.20.3"
    private lateinit var app: Context
    val status = MutableStateFlow("")          // install progress text
    val installing = MutableStateFlow(false)

    fun init(ctx: Context) { app = ctx.applicationContext }
    private fun nat(name: String) = File(app.applicationInfo.nativeLibraryDir, name)
    fun base() = File(app.filesDir, "linux")
    fun rootfs() = File(base(), "alpine")
    fun installed() = ::app.isInitialized && File(rootfs(), "bin/busybox").exists() && File(rootfs(), ".blaze_ok").exists()
    fun supported() = ::app.isInitialized && nat("libproot.so").exists() && nat("libproot_loader.so").exists()

    private fun arch() = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("arm64") == true) "aarch64" else "armv7"

    suspend fun install(): String = withContext(Dispatchers.IO) {
        if (!supported()) return@withContext "This phone's CPU isn't supported for Linux."
        if (installing.value) {   // another caller (e.g. the setup prefetch) is on it: wait for it instead of skipping
            var waited = 0
            while (installing.value && waited < 600) { kotlinx.coroutines.delay(1000); waited++ }
            return@withContext if (installed()) "Alpine Linux $VER installed." else "Install didn't finish: ${status.value}"
        }
        if (installed()) return@withContext "Alpine Linux $VER is already installed."
        installing.value = true
        try {
            Terminal.setup()
            val url = "https://dl-cdn.alpinelinux.org/alpine/v${VER.substringBeforeLast('.')}/releases/${arch()}/alpine-minirootfs-$VER-${arch()}.tar.gz"
            val tgz = File(app.cacheDir, "alpine.tar.gz")
            status.value = "Downloading Alpine Linux…"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            val total = c.contentLengthLong
            c.inputStream.use { inp -> tgz.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var n: Int; var got = 0L
                while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); got += n
                    if (total > 0) status.value = "Downloading Alpine Linux… ${got * 100 / total}%" }
            } }
            status.value = "Unpacking…"
            rootfs().deleteRecursively(); rootfs().mkdirs()
            val n = try { untar(tgz, rootfs()) } catch (e: Exception) {
                tgz.delete(); status.value = "Unpacking failed: ${e.message}. Tap Install to retry."
                return@withContext "Unpacking failed: ${e.message}"
            }
            tgz.delete()
            if (!File(rootfs(), "bin/busybox").exists()) {
                status.value = "Unpacking failed ($n files). Tap Install to retry."
                return@withContext "Unpacking failed: busybox missing after $n files."
            }
            prepare()
            File(rootfs(), ".blaze_ok").writeText(VER)
            status.value = "Installed. Run: apk update && apk add python3"
            "Alpine Linux $VER installed."
        } catch (e: Exception) {
            status.value = "Install failed: ${e.message}. Tap Install to retry."
            "Install failed: ${e.message}"
        } finally { installing.value = false }
    }

    /**
     * Pure-Kotlin .tar.gz unpacker (files, folders, symlinks, hard links, modes). Replaces the
     * busybox `tar` call, which could stall or die silently on some phones and left "Unpacking…".
     */
    private fun untar(tgz: File, dest: File): Int {
        val root = dest.canonicalPath
        var count = 0; var longName: String? = null; var longLink: String? = null
        val hard = mutableListOf<Pair<File, String>>()
        java.util.zip.GZIPInputStream(java.io.BufferedInputStream(tgz.inputStream(), 64 * 1024)).use { inp ->
            val hdr = ByteArray(512)
            fun readFully(b: ByteArray, len: Int): Boolean { var o = 0; while (o < len) { val r = inp.read(b, o, len - o); if (r < 0) return false; o += r }; return true }
            fun str(o: Int, l: Int): String { var e = o; while (e < o + l && hdr[e] != 0.toByte()) e++; return String(hdr, o, e - o, Charsets.UTF_8) }
            fun oct(o: Int, l: Int): Long = str(o, l).trim().ifEmpty { "0" }.toLong(8)
            fun skip(n: Long) { var left = n; val b = ByteArray(8192); while (left > 0) { val r = inp.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) break; left -= r } }
            while (true) {
                if (!readFully(hdr, 512)) break
                if (hdr.all { it == 0.toByte() }) break
                val size = oct(124, 12); val type = hdr[156].toInt().toChar()
                val padded = (size + 511) / 512 * 512
                if (type == 'L' || type == 'K') {               // GNU long name / long link
                    val b = ByteArray(padded.toInt()); readFully(b, padded.toInt())
                    val v = String(b, 0, size.toInt(), Charsets.UTF_8).trimEnd('\u0000')
                    if (type == 'L') longName = v else longLink = v; continue
                }
                if (type == 'x' || type == 'g') { skip(padded); continue }   // pax headers: not needed for Alpine
                val prefix = str(345, 155)
                var name = longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + str(0, 100)
                val link = longLink ?: str(157, 100)
                longName = null; longLink = null
                name = name.removePrefix("./").trimEnd('/')
                val mode = (oct(100, 8) and 0xFFF).toInt()
                if (name.isEmpty()) { skip(padded); continue }
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(root) && type != '2') { skip(padded); continue }
                out.parentFile?.mkdirs()
                when (type) {
                    '5' -> { out.mkdirs(); runCatching { Os.chmod(out.path, mode or 0b111_000_000) } }
                    '2' -> { runCatching { out.delete(); Os.symlink(link, out.path) } }
                    '1' -> hard += out to link.removePrefix("./")
                    '0', '\u0000', '7' -> {
                        out.delete()
                        out.outputStream().use { o -> var left = size; val b = ByteArray(64 * 1024)
                            while (left > 0) { val r = inp.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) throw java.io.EOFException("archive cut short"); o.write(b, 0, r); left -= r } }
                        skip(padded - size)
                        runCatching { Os.chmod(out.path, mode or 0b110_000_000) }
                    }
                    else -> skip(padded)
                }
                count++
                if (count % 50 == 0) status.value = "Unpacking… $count files"
            }
        }
        for ((f, target) in hard) runCatching { File(dest, target).copyTo(f, overwrite = true); Os.chmod(f.path, Os.stat(File(dest, target).path).st_mode and 0xFFF) }
        return count
    }

    /** DNS, hosts, a profile, and stand-ins for /proc files Android hides from apps. */
    private fun prepare() {
        val r = rootfs()
        File(r, "etc/resolv.conf").apply { delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(r, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(r, "etc/profile.d").mkdirs()
        File(r, "etc/profile.d/blaze.sh").writeText("export PS1='alpine:\\w\\$ '\nexport LANG=C.UTF-8\n")
        File(r, "root").mkdirs(); File(r, "tmp").mkdirs()
        val fake = File(base(), "fake").apply { mkdirs() }
        File(fake, "stat").writeText("cpu  1000 0 1000 100000 0 0 0 0 0 0\n" + (0 until Runtime.getRuntime().availableProcessors()).joinToString("") { "cpu$it 250 0 250 25000 0 0 0 0 0 0\n" } +
            "intr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        File(fake, "uptime").writeText("1000.00 900.00\n")
        File(fake, "version").writeText("Linux version 5.10.0 (blaze@android) #1 SMP\n")
        val lib = File(base(), "lib").apply { mkdirs() }
        val talloc = File(lib, "libtalloc.so.2")
        // An app update moves nativeLibraryDir (/data/app/~~random/...), leaving this link dangling: exists() is false
        // but the link itself is still there, so a plain symlink() failed with EEXIST and proot couldn't start.
        // Always point it at the current install.
        val target = nat("libtalloc.so").absolutePath
        val current = try { Os.readlink(talloc.absolutePath) } catch (_: Exception) { null }
        if (current != target || !talloc.exists()) {
            try { Os.remove(talloc.absolutePath) } catch (_: Exception) { talloc.delete() }
            try { Os.symlink(target, talloc.absolutePath) } catch (_: Exception) {
                try { File(target).copyTo(talloc, overwrite = true) } catch (_: Exception) { }
            }
        }
    }

    /** The command line that runs [cmd] (or a login shell) inside Alpine. */
    fun command(cmd: String?): List<String> {
        prepare()
        val r = rootfs().absolutePath
        val fake = File(base(), "fake")
        val args = mutableListOf(nat("libproot.so").absolutePath, "--kill-on-exit", "--link2symlink", "-0", "-r", r,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/system", "-b", "/apex",
            "-b", "${app.filesDir.absolutePath}/home:/root/phone")
        if (!File("/proc/stat").canRead()) { args += "-b"; args += "${fake.absolutePath}/stat:/proc/stat" }
        if (!File("/proc/uptime").canRead()) { args += "-b"; args += "${fake.absolutePath}/uptime:/proc/uptime" }
        if (!File("/proc/version").canRead()) { args += "-b"; args += "${fake.absolutePath}/version:/proc/version" }
        if (File("/sdcard").canRead()) { args += "-b"; args += "/sdcard" }
        args += listOf("-w", "/root", "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        args += if (cmd == null) listOf("/bin/sh", "-l") else listOf("/bin/sh", "-lc", cmd)
        return args
    }

    fun env(): Map<String, String> {
        val tmp = File(app.cacheDir, "proot").apply { mkdirs() }
        return buildMap {
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("PROOT_LOADER", nat("libproot_loader.so").absolutePath)
            if (nat("libproot_loader32.so").exists()) put("PROOT_LOADER_32", nat("libproot_loader32.so").absolutePath)
            put("LD_LIBRARY_PATH", File(base(), "lib").absolutePath)
            put("PROOT_NO_SECCOMP", "0")
        }
    }

    fun uninstall() { rootfs().deleteRecursively(); status.value = "Removed." }
    fun sizeMb(): Long = try { rootfs().walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000 } catch (_: Exception) { 0 }
}
