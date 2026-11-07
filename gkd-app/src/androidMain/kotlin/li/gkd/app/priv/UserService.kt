package li.gkd.app.priv

import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ServiceSpecificException
import androidx.annotation.Keep
import li.gkd.aidl.IInstallResultListener
import li.gkd.aidl.ISnapshotKeyListener
import li.gkd.aidl.ISystemSurfaceListener
import li.gkd.aidl.IUserService

private const val SCREENSHOT_ERROR_CODE = 1
private const val SYSTEM_SURFACE_LISTENER_ERROR_CODE = 2
private const val INSTALL_RESULT_SENDER_ERROR_CODE = 3
private const val SNAPSHOT_KEY_LISTENER_ERROR_CODE = 4
private const val MAX_SCREENSHOT_ERROR_LENGTH = 16_384

@Keep
class UserService : IUserService.Stub() {
    private var systemSurfaceListener: PrivilegedSystemSurfaceListener? = null
    private var snapshotKeyMonitor: RootSnapshotKeyMonitor? = null

    @Synchronized
    override fun setSystemSurfaceListener(listener: ISystemSurfaceListener?) {
        try {
            systemSurfaceListener?.close()
            systemSurfaceListener = null
            if (listener != null && android.os.Build.VERSION.SDK_INT >= 34) {
                val next = PrivilegedSystemSurfaceListener(listener)
                systemSurfaceListener = next
                next.start()
            }
        } catch (e: Throwable) {
            throw ServiceSpecificException(
                SYSTEM_SURFACE_LISTENER_ERROR_CODE,
                e.stackTraceToString().take(MAX_SCREENSHOT_ERROR_LENGTH),
            )
        }
    }

    override fun takeScreenshot(crop: Rect, rotation: Int): Bitmap? {
        return try {
            CompatScreenshot.captureBySurfaceControl(crop, rotation)
        } catch (e: Throwable) {
            throw ServiceSpecificException(
                SCREENSHOT_ERROR_CODE,
                e.stackTraceToString().take(MAX_SCREENSHOT_ERROR_LENGTH),
            )
        }
    }

    @Synchronized
    override fun setSnapshotKeyListener(listener: ISnapshotKeyListener?): Boolean {
        try {
            snapshotKeyMonitor?.close()
            snapshotKeyMonitor = null
            if (listener == null) return true
            val monitor = RootSnapshotKeyMonitor(listener::onSnapshotRequested)
            if (!monitor.start()) {
                monitor.close()
                return false
            }
            snapshotKeyMonitor = monitor
            return true
        } catch (e: Throwable) {
            throw ServiceSpecificException(
                SNAPSHOT_KEY_LISTENER_ERROR_CODE,
                e.stackTraceToString().take(MAX_SCREENSHOT_ERROR_LENGTH),
            )
        }
    }

    override fun destroy() {
        try {
            setSystemSurfaceListener(null)
        } finally {
            setSnapshotKeyListener(null)
        }
    }

    override fun createInstallResultSender(
        launchIntent: Intent,
        callingPackage: String,
        userId: Int,
        listener: IInstallResultListener,
    ): IntentSender =
        try {
            InstallRestartReceiver(launchIntent, callingPackage, userId, listener).intentSender()
        } catch (e: Throwable) {
            throw ServiceSpecificException(
                INSTALL_RESULT_SENDER_ERROR_CODE,
                e.stackTraceToString().take(MAX_SCREENSHOT_ERROR_LENGTH),
            )
        }
}
