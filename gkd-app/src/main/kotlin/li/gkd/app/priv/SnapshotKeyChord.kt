package li.gkd.app.priv

/** Linux input key codes, not Android KeyEvent codes. */
class SnapshotKeyChord {
    private var powerDown: Long? = null
    private var volumeUpDown: Long? = null
    private var fired = false

    @Synchronized
    fun onKey(code: Int, value: Int, timeMillis: Long): Boolean {
        if (code != 116 && code != 115) return false
        if (value != 0 && value != 1) return false
        if (code == 116) {
            if (value == 0) powerDown = null
            else if (powerDown == null) powerDown = timeMillis
        } else {
            if (value == 0) volumeUpDown = null
            else if (volumeUpDown == null) volumeUpDown = timeMillis
        }
        if (powerDown == null && volumeUpDown == null) fired = false
        val power = powerDown ?: return false
        val volume = volumeUpDown ?: return false
        if (fired || kotlin.math.abs(power - volume) > 250) return false
        fired = true
        return true
    }
}
