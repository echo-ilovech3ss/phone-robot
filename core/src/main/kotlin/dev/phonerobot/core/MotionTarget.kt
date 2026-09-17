package dev.phonerobot.core

/** Viewer coordinates, not servo angles. Hardware adapters must calibrate independently. */
data class MotionTarget(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in -1f..1f && y in -1f..1f) }
    companion object {
        fun bounded(x: Float, y: Float): MotionTarget? =
            if (x.isFinite() && y.isFinite()) MotionTarget(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f)) else null
    }
}

data class MotionGate(val enabled: Boolean = false) {
    fun resume() = copy(enabled = true)
    fun stop() = copy(enabled = false)
    fun accept(x: Float, y: Float): MotionTarget? = if (enabled) MotionTarget.bounded(x, y) else null
}

/** Future adapters consume bounded requests only; cloud output never enters this interface. */
interface RobotHardware {
    fun look(target: MotionTarget)
    fun stop()
}
