package li.gkd.app.priv

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.FileObserver
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import java.io.File

@Keep
class UserService : IUserService.Stub() {
    private var screenshotObserver: FileObserver? = null
    private var lastScreenshotEventTime = 0L
    private var snapshotKeyMonitor: RootSnapshotKeyMonitor? = null

    override fun setSnapshotKeyListener(listener: IScreenshotListener): Boolean {
        clearSnapshotKeyListener()
        val monitor = RootSnapshotKeyMonitor { listener.onScreenshot() }
        return if (monitor.start()) {
            snapshotKeyMonitor = monitor
            true
        } else {
            monitor.close()
            false
        }
    }

    override fun clearSnapshotKeyListener() {
        snapshotKeyMonitor?.close()
        snapshotKeyMonitor = null
    }

    override fun takeScreenshot(crop: Rect, rotation: Int): Bitmap? {
        return CompatScreenshot.captureBySurfaceControl(crop, rotation)
    }

    override fun setScreenshotFileListener(
        directoryPath: String,
        listener: IScreenshotListener,
    ): Boolean {
        clearScreenshotFileListener()
        val directory = File(directoryPath)
        if (!directory.isDirectory) return false
        val observer = object : FileObserver(
            directory.absolutePath,
            CLOSE_WRITE or MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) {
                val eventType = event and ALL_EVENTS
                if (eventType != CLOSE_WRITE && eventType != MOVED_TO) return
                val eventPath = path ?: return
                if (eventPath.substringAfterLast('.', "").lowercase() !in SCREENSHOT_EXTENSIONS) return
                if (eventPath.startsWith(SCREENSHOT_PENDING_PREFIX)) return
                val now = SystemClock.elapsedRealtime()
                synchronized(this@UserService) {
                    if (now - lastScreenshotEventTime < SCREENSHOT_EVENT_DEBOUNCE) return
                    lastScreenshotEventTime = now
                }
                Log.i(SCREENSHOT_LOG_TAG, "detected event=$eventType path=$eventPath")
                runCatching { listener.onScreenshot() }
            }
        }
        return try {
            observer.startWatching()
            screenshotObserver = observer
            Log.i(
                SCREENSHOT_LOG_TAG,
                "watching uid=${Process.myUid()} pid=${Process.myPid()} path=${directory.absolutePath}",
            )
            true
        } catch (e: Throwable) {
            observer.stopWatching()
            Log.e(SCREENSHOT_LOG_TAG, "watch failed", e)
            false
        }
    }

    override fun clearScreenshotFileListener() {
        screenshotObserver?.stopWatching()
        screenshotObserver = null
    }

    override fun destroy() {
        clearScreenshotFileListener()
        clearSnapshotKeyListener()
    }
}

private const val SCREENSHOT_LOG_TAG = "GkdRootScreenshot"
private const val SCREENSHOT_PENDING_PREFIX = ".pending-"
private const val SCREENSHOT_EVENT_DEBOUNCE = 1_500L
private val SCREENSHOT_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
