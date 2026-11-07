package li.gkd.app.matching.query

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MatchNodeReaderCapacityTest {
    @Test
    fun customSingleSlotRejectsAnotherReadWhileTimedOutBinderCallIsStillRunning() {
        val reader = MatchNodeReader(capacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertEquals(
                MatchNodeReadFailure.Timeout,
                assertFailsWith<MatchNodeReadUnavailable> {
                        reader.read(50) {
                            entered.countDown()
                            release.await()
                        }
                    }
                    .reason,
            )
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(
                MatchNodeReadFailure.CapacityExceeded,
                assertFailsWith<MatchNodeReadUnavailable> {
                        reader.read { error("Occupied reader must reject another read") }
                    }
                    .reason,
            )
        } finally {
            release.countDown()
            reader.close()
        }
    }
}
