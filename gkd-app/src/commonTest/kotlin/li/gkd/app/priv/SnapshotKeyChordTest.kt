package li.gkd.app.priv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotKeyChordTest {
    @Test
    fun eitherOrderTriggersOnceUntilBothReleased() {
        for ((first, second) in listOf(115 to 116, 116 to 115)) {
            val chord = SnapshotKeyChord()
            assertFalse(chord.onKey(first, 1, 1_000))
            assertTrue(chord.onKey(second, 1, 1_100))
            assertFalse(chord.onKey(second, 2, 1_200))
            assertFalse(chord.onKey(second, 1, 1_201))
            assertFalse(chord.onKey(second, 0, 1_210))
            assertFalse(chord.onKey(second, 1, 1_220))
            chord.onKey(first, 0, 1_300)
            chord.onKey(second, 0, 1_300)
            assertFalse(chord.onKey(first, 1, 1_400))
            assertTrue(chord.onKey(second, 1, 1_450))
        }
    }

    @Test
    fun unrelatedAndSeparatedKeysDoNotTrigger() {
        val chord = SnapshotKeyChord()
        assertFalse(chord.onKey(114, 1, 1_000))
        assertFalse(chord.onKey(116, 1, 1_050))
        assertFalse(chord.onKey(115, 1, 1_400))
        chord.onKey(115, 0, 1_500)
        chord.onKey(116, 0, 1_500)
        assertFalse(chord.onKey(115, 1, 2_000))
        chord.onKey(115, 0, 2_100)
        assertFalse(chord.onKey(116, 1, 2_200))
    }
}
