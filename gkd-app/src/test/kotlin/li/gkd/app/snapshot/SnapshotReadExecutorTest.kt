package li.gkd.app.snapshot

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SnapshotReadExecutorTest {
    @Test(timeout = 5000)
    fun timeoutReleasesWaiterWithoutQueuingMoreBlockedReads() = runBlocking {
        val reader = SnapshotReadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val captureMutex = Mutex()
        try {
            val capture = async(start = CoroutineStart.UNDISPATCHED) {
                captureMutex.lock()
                try {
                    withTimeoutOrNull(150L) {
                        reader.read {
                            calls.incrementAndGet()
                            entered.countDown()
                            release.await()
                            "expired snapshot"
                        }
                    }
                } finally {
                    captureMutex.unlock()
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull(capture.await())
            assertFalse(captureMutex.isLocked)
            repeat(20) {
                try {
                    reader.read { calls.incrementAndGet() }
                    fail("A second request must not queue behind the blocked read")
                } catch (_: SnapshotReadBusyException) {
                    // Expected: no extra task or worker is created.
                }
            }
            assertEquals(1, calls.get())
            release.countDown()
            val fresh = withTimeout(1000L) {
                var value: String? = null
                while (value == null) {
                    try {
                        value = reader.read { "fresh snapshot" }
                    } catch (_: SnapshotReadBusyException) {
                        delay(1L)
                    }
                }
                value
            }
            assertEquals("fresh snapshot", fresh)
            assertNull(capture.await()) // The late Binder result never revives the expired capture.
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 3000)
    fun failedReadDoesNotBlockNextRequest() = runBlocking {
        val reader = SnapshotReadExecutor()
        try {
            reader.read<Int> { error("disconnected") }
            fail("Expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("disconnected", e.message)
        }
        assertEquals(42, reader.read { 42 })
    }
}
