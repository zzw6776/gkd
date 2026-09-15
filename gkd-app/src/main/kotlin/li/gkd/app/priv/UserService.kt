package li.gkd.app.priv

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.annotation.Keep
import java.io.File

@Keep
class UserService : IUserService.Stub() {
    private var screenshotMonitor: ScreenshotDirectoryMonitor? = null
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

    @Synchronized
    override fun setScreenshotFileListener(
        directoryPath: String,
        listener: IScreenshotFileListener,
    ): Boolean {
        clearScreenshotFileListener()
        val monitor = ScreenshotDirectoryMonitor(
            directory = File(directoryPath).absoluteFile,
            onScreenshot = {
                runCatching { listener.onScreenshot() }
            },
            onState = { watching, message ->
                Log.i("GkdRootScreenshot", message)
                runCatching { listener.onStateChanged(watching, message) }
            },
        )
        return if (monitor.start()) {
            screenshotMonitor = monitor
            true
        } else {
            monitor.close()
            false
        }
    }

    @Synchronized
    override fun clearScreenshotFileListener() {
        screenshotMonitor?.close()
        screenshotMonitor = null
    }

    override fun destroy() {
        clearScreenshotFileListener()
        clearSnapshotKeyListener()
    }
}
