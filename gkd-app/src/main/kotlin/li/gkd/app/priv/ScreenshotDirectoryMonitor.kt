package li.gkd.app.priv

import android.os.FileObserver
import java.io.Closeable
import java.io.File

/** Rebinds only on filesystem events, including creation of initially missing directories. */
class ScreenshotDirectoryMonitor(
    private val directory: File,
    private val onScreenshot: () -> Unit,
    private val onState: (Boolean, String) -> Unit,
    private val watch: (File, (Int, String?) -> Unit) -> Closeable = ::watchDirectory,
) : Closeable {
    private val watches = mutableMapOf<File, Closeable>()
    private var closed = false
    private var startedAt = 0L
    private var lastScreenshotAt: Long? = null

    @Synchronized
    fun start(): Boolean {
        startedAt = System.currentTimeMillis()
        return reconcile()
    }

    private fun reconcile(): Boolean {
        if (closed) return false
        val wasWatchingTarget = directory in watches
        val anchor = generateSequence(directory) { it.parentFile }.firstOrNull { it.isDirectory }
        if (anchor == null) return fail("无法访问存储目录")
        val desired = if (anchor == directory) {
            listOfNotNull(directory.parentFile, directory)
        } else {
            listOf(anchor)
        }
        try {
            // Attach before detaching the old ancestor, avoiding a gap during directory creation.
            desired.filterNot(watches::containsKey).forEach { path ->
                watches[path] = watch(path) { event, name -> onEvent(path, event, name) }
            }
            (watches.keys - desired.toSet()).forEach { watches.remove(it)?.close() }
            val ready = directory in watches
            onState(ready, if (ready) "已注册截图目录监听" else "等待截图目录创建")
            if (ready && !wasWatchingTarget) {
                // A first screenshot can finish while the new directory watch is being attached.
                directory.listFiles()?.filter { it.lastModified() >= startedAt && it.isFile }
                    ?.forEach { notifyScreenshot(it.name) }
            }
            return true
        } catch (e: Exception) {
            return fail(e.message ?: "目录监听注册失败")
        }
    }

    @Synchronized
    private fun onEvent(watched: File, event: Int, name: String?) {
        if (closed || watched !in watches) return
        val type = event and FileObserver.ALL_EVENTS
        if (type == FileObserver.DELETE_SELF || type == FileObserver.MOVE_SELF) {
            watches.remove(watched)?.close()
            reconcile()
        } else if (watched == directory) {
            if (type == FileObserver.CLOSE_WRITE || type == FileObserver.MOVED_TO) {
                name?.let(::notifyScreenshot)
            }
        } else if (
            type == FileObserver.CREATE || type == FileObserver.MOVED_TO ||
            type == FileObserver.DELETE || type == FileObserver.MOVED_FROM
        ) {
            val next = directory.relativeTo(watched).path.substringBefore(File.separatorChar)
            if (name == next) reconcile()
        }
    }

    private fun notifyScreenshot(name: String) {
        if (name.startsWith(".pending-")) return
        if (name.substringAfterLast('.', "").lowercase() !in extensions) return
        val now = System.nanoTime() / 1_000_000
        if (lastScreenshotAt?.let { now - it < 1500 } == true) return
        lastScreenshotAt = now
        onScreenshot()
    }

    private fun fail(message: String): Boolean {
        watches.values.forEach { it.close() }
        watches.clear()
        onState(false, "监听失败：$message；请重新开启 Root 截屏监听")
        return false
    }

    @Synchronized
    override fun close() {
        closed = true
        watches.values.forEach { it.close() }
        watches.clear()
    }
}

private val extensions = setOf("png", "jpg", "jpeg", "webp")

private fun watchDirectory(directory: File, callback: (Int, String?) -> Unit): Closeable {
    check(directory.isDirectory && directory.canRead()) { "无法读取 ${directory.path}" }
    val observer = object : FileObserver(
        directory.path,
        CLOSE_WRITE or MOVED_TO or CREATE or DELETE or MOVED_FROM or DELETE_SELF or MOVE_SELF,
    ) {
        override fun onEvent(event: Int, path: String?) = callback(event, path)
    }
    observer.startWatching()
    return Closeable { observer.stopWatching() }
}
