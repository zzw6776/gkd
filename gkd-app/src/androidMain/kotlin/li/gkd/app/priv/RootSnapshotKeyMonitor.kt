package li.gkd.app.priv

import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Passive readers of only input devices advertising the two shortcut keys. */
class RootSnapshotKeyMonitor(private val onPressed: () -> Unit) : Closeable {
    private val readers = mutableListOf<Process>()
    private val chord = SnapshotKeyChord()
    @Volatile private var closed = false

    fun start(): Boolean {
        val discovery =
            ProcessBuilder("/system/bin/getevent", "-pl").redirectErrorStream(true).start()
        val output =
            try {
                if (!discovery.waitFor(3, TimeUnit.SECONDS)) return false
                if (discovery.exitValue() != 0) return false
                discovery.inputStream.bufferedReader().use { it.readText() }
            } finally {
                discovery.destroy()
            }
        val devices =
            output.split(Regex("(?=add device )")).mapNotNull { block ->
                val path =
                    Regex("add device \\d+: (/dev/input/event\\d+)")
                        .find(block)
                        ?.groupValues
                        ?.get(1) ?: return@mapNotNull null
                path to block
            }
        if (
            devices.none { "KEY_POWER" in it.second } ||
                devices.none { "KEY_VOLUMEUP" in it.second }
        ) {
            return false
        }
        try {
            devices
                .filter { "KEY_POWER" in it.second || "KEY_VOLUMEUP" in it.second }
                .forEach { (path, _) ->
                    val process =
                        ProcessBuilder("/system/bin/getevent", "-q", path)
                            .redirectErrorStream(true)
                            .start()
                    readers.add(process)
                    thread(name = "SnapshotKeys-${path.substringAfterLast('/')}", isDaemon = true) {
                        try {
                            process.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    if (closed) return@forEach
                                    val values = line.trim().split(Regex("\\s+"))
                                    if (values.size != 3 || values[0] != "0001") return@forEach
                                    val code = values[1].toIntOrNull(16) ?: return@forEach
                                    val value = values[2].toIntOrNull(16) ?: return@forEach
                                    if (chord.onKey(code, value, SystemClock.elapsedRealtime())) {
                                        Log.i(TAG, "volume-up + power")
                                        runCatching(onPressed).onFailure {
                                            Log.e(TAG, "snapshot callback failed", it)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (!closed) Log.e(TAG, "input reader failed: $path", e)
                        } finally {
                            process.destroy()
                            if (!closed) Log.w(TAG, "input reader stopped: $path")
                        }
                    }
                }
            Log.i(TAG, "listening on ${readers.size} key devices")
            return true
        } catch (e: Exception) {
            close()
            Log.e(TAG, "start failed", e)
            return false
        }
    }

    override fun close() {
        closed = true
        readers.forEach(Process::destroy)
        readers.clear()
    }

    private companion object {
        const val TAG = "GkdSnapshotKeys"
    }
}
