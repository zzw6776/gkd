package li.gkd.app.priv

/** Linux input key codes, not Android KeyEvent codes. */
class SnapshotKeyChord {
    private var powerDown: Long? = null
    private var volumeUpDown: Long? = null
    private var fired = false

    @Synchronized
    fun onKey(code: Int, value: Int, timeMillis: Long): Boolean {
        if (code != POWER_KEY_CODE && code != VOLUME_UP_KEY_CODE) return false
        if (value != KEY_UP && value != KEY_DOWN) return false
        if (code == POWER_KEY_CODE) {
            if (value == KEY_UP) powerDown = null else if (powerDown == null) powerDown = timeMillis
        } else {
            if (value == KEY_UP) volumeUpDown = null
            else if (volumeUpDown == null) volumeUpDown = timeMillis
        }
        if (powerDown == null && volumeUpDown == null) fired = false
        val power = powerDown ?: return false
        val volume = volumeUpDown ?: return false
        if (fired || kotlin.math.abs(power - volume) > MAX_CHORD_INTERVAL_MILLIS) return false
        fired = true
        return true
    }

    private companion object {
        const val POWER_KEY_CODE = 116
        const val VOLUME_UP_KEY_CODE = 115
        const val KEY_UP = 0
        const val KEY_DOWN = 1
        const val MAX_CHORD_INTERVAL_MILLIS = 250
    }
}
