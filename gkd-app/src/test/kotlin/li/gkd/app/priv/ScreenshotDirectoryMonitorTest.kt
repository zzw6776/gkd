package li.gkd.app.priv

import android.os.FileObserver
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File

class ScreenshotDirectoryMonitorTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Events {
        val callbacks = mutableMapOf<File, (Int, String?) -> Unit>()
        fun watch(path: File, callback: (Int, String?) -> Unit): Closeable {
            callbacks[path] = callback
            return Closeable { callbacks.remove(path) }
        }
        fun emit(path: File, event: Int, name: String? = null) {
            checkNotNull(callbacks[path])(event, name)
        }
    }

    @Test fun missingParentsAreAttachedOnCreationAndFirstCompletedImageIsCaught() {
        val events = Events()
        val pictures = File(temporary.root, "Pictures")
        val target = File(pictures, "Screenshots")
        var watching = false
        var captures = 0
        val monitor = ScreenshotDirectoryMonitor(target, { captures++ }, { ready, _ ->
            watching = ready
        }, events::watch)
        monitor.use {
            assertTrue(it.start())
            assertFalse(watching)
            assertEquals(setOf(temporary.root), events.callbacks.keys)
            assertTrue(pictures.mkdir())
            events.emit(temporary.root, FileObserver.CREATE, "Pictures")
            assertEquals(setOf(pictures), events.callbacks.keys)
            assertTrue(target.mkdir())
            File(target, "first.png").writeText("finished before watch attachment")
            events.emit(pictures, FileObserver.CREATE, "Screenshots")
            assertTrue(watching)
            assertEquals(setOf(pictures, target), events.callbacks.keys)
            assertEquals(1, captures)
            events.emit(target, FileObserver.CLOSE_WRITE, "first.png")
            assertEquals(1, captures) // Catch-up and close events must not duplicate the snapshot.
        }
        assertTrue(events.callbacks.isEmpty())
    }

    @Test fun deletionReturnsToWaitingAndRecreationRestoresWatch() {
        val events = Events()
        val target = temporary.newFolder("Screenshots")
        var watching = false
        val monitor = ScreenshotDirectoryMonitor(target, {}, { ready, _ -> watching = ready }, events::watch)
        monitor.use {
            assertTrue(it.start())
            assertTrue(watching)
            assertTrue(target.delete())
            events.emit(temporary.root, FileObserver.DELETE, "Screenshots")
            assertFalse(watching)
            assertEquals(setOf(temporary.root), events.callbacks.keys)
            assertTrue(target.mkdir())
            events.emit(temporary.root, FileObserver.CREATE, "Screenshots")
            assertTrue(watching)
        }
    }

    @Test fun existingImagesPendingFilesAndEventsAfterCloseDoNotCapture() {
        val events = Events()
        val target = temporary.newFolder("Screenshots")
        File(target, "old.png").apply { writeText("old"); setLastModified(1L) }
        var captures = 0
        val monitor = ScreenshotDirectoryMonitor(target, { captures++ }, { _, _ -> }, events::watch)
        assertTrue(monitor.start())
        events.emit(target, FileObserver.CLOSE_WRITE, ".pending-1-new.png")
        events.emit(target, FileObserver.CLOSE_WRITE, "note.txt")
        assertEquals(0, captures)
        val lateEvent = checkNotNull(events.callbacks[target])
        monitor.close()
        lateEvent(FileObserver.CLOSE_WRITE, "new.png")
        assertEquals(0, captures)
    }

    @Test fun watchRegistrationFailureIsReportedAndPartialWatchesAreClosed() {
        val target = temporary.newFolder("Screenshots")
        val events = Events()
        var message = ""
        val monitor = ScreenshotDirectoryMonitor(target, {}, { _, text -> message = text }) { path, callback ->
            if (path == target) error("permission denied")
            events.watch(path, callback)
        }
        monitor.use {
            assertFalse(it.start())
            assertTrue(message.contains("监听失败"))
            assertTrue(events.callbacks.isEmpty())
        }
    }
}
