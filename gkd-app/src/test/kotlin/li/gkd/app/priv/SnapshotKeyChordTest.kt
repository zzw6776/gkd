package li.gkd.app.priv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotKeyChordTest {
    @Test fun eitherOrderTriggersOnceUntilBothReleased() {
        for ((first, second) in listOf(115 to 116, 116 to 115)) {
            val chord = SnapshotKeyChord()
            assertFalse(chord.onKey(first, 1, 1000))
            assertTrue(chord.onKey(second, 1, 1100))
            assertFalse(chord.onKey(second, 2, 1200))
            assertFalse(chord.onKey(second, 1, 1201))
            assertFalse(chord.onKey(second, 0, 1210))
            assertFalse(chord.onKey(second, 1, 1220))
            chord.onKey(first, 0, 1300)
            chord.onKey(second, 0, 1300)
            assertFalse(chord.onKey(first, 1, 1400))
            assertTrue(chord.onKey(second, 1, 1450))
        }
    }

    @Test fun unrelatedAndSeparatedKeysDoNotTrigger() {
        val chord = SnapshotKeyChord()
        assertFalse(chord.onKey(114, 1, 1000))
        assertFalse(chord.onKey(116, 1, 1050))
        assertFalse(chord.onKey(115, 1, 1400))
        chord.onKey(115, 0, 1500)
        chord.onKey(116, 0, 1500)
        assertFalse(chord.onKey(115, 1, 2000))
        chord.onKey(115, 0, 2100)
        assertFalse(chord.onKey(116, 1, 2200))
    }
}
