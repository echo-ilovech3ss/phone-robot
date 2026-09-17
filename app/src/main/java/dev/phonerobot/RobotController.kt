package dev.phonerobot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.phonerobot.cloud.CloudClient
import dev.phonerobot.cloud.CloudSettings
import dev.phonerobot.core.Command
import dev.phonerobot.core.CommandRouter
import dev.phonerobot.core.MotionGate
import dev.phonerobot.core.MotionTarget
import dev.phonerobot.core.RobotHardware
import dev.phonerobot.speech.RobotSpeech
import dev.phonerobot.vision.FaceObservation
import java.text.DateFormat
import java.util.Date

/** Main-thread coordinator. Only explicit local commands can produce movement requests. */
class RobotController(
    private val context: Context,
    private val speech: RobotSpeech,
    private val cloud: CloudClient,
    private val hardware: RobotHardware,
    private val onMode: (String) -> Unit,
    private val onReply: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val setCamera: (Boolean) -> Unit,
) {
    var settings = CloudSettings()
    private var gate = MotionGate()
    private var tracking = false
    private var sleeping = false
    val isSleeping: Boolean get() = sleeping
    private var foreground = false
    private var thinking = false
    private var speaking = false
    private var listening = false

    fun resume() { foreground = true; setCamera(!sleeping); updateMode() }
    fun pause() {
        foreground = false
        interrupt()
        gate = gate.stop(); tracking = false; hardware.stop(); setCamera(false)
    }
    fun beginListening() {
        if (!foreground || sleeping) return
        interrupt()
        speech.listen()
    }
    fun listeningChanged(value: Boolean) { listening = value; updateMode() }
    fun speakingChanged(value: Boolean) { speaking = value; updateMode() }
    fun face(face: FaceObservation?) {
        if (!foreground || sleeping || !tracking) return
        if (face == null) {
            hardware.look(MotionTarget(0f, 0f))
            return
        }
        gate.accept(face.x, face.y)?.let(hardware::look)
    }
    fun submit(text: String) {
        if (!foreground) return
        val command = CommandRouter.parse(text)
        if (command == Command.EMPTY) return
        interrupt()
        onTranscript(text.take(1000))
        if (command == Command.INVALID) { onReply("Please keep requests under 1000 characters."); return }
        if (command == Command.STOP) { stop(); return }
        if (command == Command.WAKE) {
            sleeping = false; setCamera(true); reply("I'm awake. Say track me to follow your face."); return
        }
        if (sleeping) { reply("I'm sleeping. Say wake up to resume."); return }
        when (command) {
            Command.SLEEP -> { sleeping = true; stopMotion(); setCamera(false); reply("Going to sleep.") }
            Command.LEFT -> look(-1f, 0f)
            Command.RIGHT -> look(1f, 0f)
            Command.UP -> look(0f, -1f)
            Command.DOWN -> look(0f, 1f)
            Command.CENTER -> look(0f, 0f)
            Command.TRACK -> { gate = gate.resume(); tracking = true; reply("Following faces on screen. No servos are connected.") }
            Command.HELLO -> reply("Hello! I'm your phone robot.")
            Command.HELP -> reply("Try track me, look left, look right, look up, look down, look center, time, battery, sleep, wake up, or stop.")
            Command.TIME -> reply("It's ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}.")
            Command.BATTERY -> reply(battery())
            Command.CHAT -> converse(text)
            else -> Unit
        }
    }
    fun stop() {
        interrupt(); stopMotion(); updateMode()
        if (sleeping) {
            onReply("Stopped. Robot is currently sleeping; say wake up to resume.")
        } else {
            onReply("Stopped. Tracking and speech are off; camera remains available. Sleep turns the camera off.")
        }
    }
    fun settingsChanged(value: CloudSettings) { interrupt(); settings = value }
    private fun interrupt() {
        cloud.cancel(); thinking = false
        speech.stopListening(); speech.stopSpeaking()
        listening = false; speaking = false; updateMode()
    }
    private fun stopMotion() { gate = gate.stop(); tracking = false; hardware.stop() }
    private fun look(x: Float, y: Float) {
        tracking = false; gate = gate.resume()
        gate.accept(x, y)?.let(hardware::look)
        reply("Moving my eyes on screen. No servos are connected.")
    }
    private fun converse(text: String) {
        if (!CommandRouter.needsCloud(Command.CHAT, settings.enabled, sleeping)) {
            reply("That needs online AI. Enable a backend in AI settings, or say help for offline commands.")
            return
        }
        thinking = true; updateMode()
        cloud.ask(settings, text) { result ->
            thinking = false
            if (foreground) result.fold(::reply) { onReply(it.message ?: "AI request failed."); updateMode() }
        }
    }
    private fun reply(text: String) { onReply(text); updateMode(); speech.speak(text) }
    private fun updateMode() {
        onMode(when { !foreground -> "Paused"; speaking -> "Speaking"; listening -> "Listening";
            thinking -> "Thinking"; sleeping -> "Sleeping"; else -> "Ready" })
    }
    private fun battery(): String = runCatching {
        val info = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = info?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = info?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) "Battery is ${100 * level / scale} percent." else "Battery level is unavailable."
    }.getOrDefault("Battery level is unavailable.")
}
