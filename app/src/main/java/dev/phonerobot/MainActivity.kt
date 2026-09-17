package dev.phonerobot

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.phonerobot.cloud.CloudClient
import dev.phonerobot.cloud.CloudSettings
import dev.phonerobot.cloud.SettingsStore
import dev.phonerobot.core.MotionTarget
import dev.phonerobot.core.RobotHardware
import dev.phonerobot.speech.RobotSpeech
import dev.phonerobot.ui.RobotScreen
import dev.phonerobot.vision.RobotVision
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : ComponentActivity() {
    private lateinit var screen: RobotScreen
    private lateinit var speech: RobotSpeech
    private lateinit var vision: RobotVision
    private lateinit var controller: RobotController
    private lateinit var store: SettingsStore
    private val cloud = CloudClient()
    private var active = false
    private var cameraWanted = true
    private var cameraAllowed = true
    private var cameraRunning = false
    private var activeDialog: AlertDialog? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) syncCamera() else screen.vision.text = "Camera permission denied. Tap Camera to retry; typing still works."
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && active) controller.beginListening() else screen.speech.text = "Microphone permission denied. You can still type."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = RobotScreen(this)
        setContentView(screen.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = SettingsStore(this)
        speech = RobotSpeech(this,
            onText = { if (active) controller.submit(it) },
            onStatus = { screen.speech.text = it },
            onSpeaking = { controller.speakingChanged(it) },
            onListening = { controller.listeningChanged(it) })
        vision = RobotVision(this,
            onFace = { face ->
                controller.face(face)
                screen.vision.text = when {
                    face == null -> "No face detected"
                    face.name != null -> "${face.name} · ${face.faceCount} face(s)"
                    else -> "Unknown person · ${face.faceCount} face(s)"
                }
            }, onStatus = { screen.vision.text = it })
        val previewHardware = object : RobotHardware {
            override fun look(target: MotionTarget) { screen.face.target = target }
            override fun stop() { screen.face.target = MotionTarget(0f, 0f) }
        }
        controller = RobotController(applicationContext, speech, cloud, previewHardware,
            onMode = { screen.mode.text = it; screen.face.mode = it },
            onReply = { screen.reply.text = it },
            onTranscript = { screen.transcript.text = "You: $it" },
            setCamera = { cameraWanted = it; syncCamera() })
        try { controller.settings = store.load() }
        catch (_: Exception) { screen.reply.text = "Saved AI settings could not be read. AI is off; save new settings to recover." }
        updatePrivacy()
        bindControls()
        speech.prepare()
    }
    private fun bindControls() {
        screen.send.setOnClickListener { controller.submit(screen.input.text.toString()); screen.input.text.clear() }
        screen.listen.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.beginListening()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        screen.stop.setOnClickListener { controller.stop() }
        screen.track.setOnClickListener { controller.submit("track me") }
        screen.sleep.setOnClickListener {
            controller.submit(if (controller.isSleeping) "wake up" else "sleep")
        }
        screen.camera.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraAllowed = true; cameraPermission.launch(Manifest.permission.CAMERA)
            } else { cameraAllowed = !cameraAllowed; syncCamera() }
        }
        screen.people.setOnClickListener { showPeople() }
        screen.settings.setOnClickListener { showSettings() }
        screen.voiceSetup.setOnClickListener {
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
            catch (_: Exception) { screen.speech.text = "No TTS installer. Install an offline English TTS engine in Android settings." }
        }
    }
    override fun onResume() {
        super.onResume()
        active = true
        controller.resume()
        speech.prepare()
    }
    override fun onPause() {
        active = false
        controller.pause()
        super.onPause()
    }
    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        vision.close(); speech.close(); cloud.close()
        super.onDestroy()
    }
    private fun syncCamera() {
        val shouldRun = active && cameraWanted && cameraAllowed
        if (!shouldRun) {
            if (cameraRunning) vision.stop()
            cameraRunning = false
            screen.vision.text = "Camera off"
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            screen.vision.text = "Tap Camera on / off to grant camera permission."
            return
        }
        if (!cameraRunning) { vision.start(this, screen.preview); cameraRunning = true }
    }
    private fun showPeople() {
        val names = try { vision.names() } catch (_: Exception) {
            screen.reply.text = "Could not read enrolled people."; return
        }
        val dialog = AlertDialog.Builder(this).setTitle("People stored on this phone")
            .setItems((listOf("Enroll a consenting person") + names.map { "Forget $it" }).toTypedArray()) { _, which ->
                if (which == 0) enrollDialog() else {
                    val name = names[which - 1]
                    val confirm = AlertDialog.Builder(this).setTitle("Forget $name?")
                        .setMessage("Delete this person's local face template.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                            try { vision.forget(name); screen.reply.text = "Deleted $name's face template." }
                            catch (_: Exception) { screen.reply.text = "Could not delete face template. Try again." }
                        }.create()
                    showDialog(confirm)
                }
            }.setNegativeButton("Close", null).create()
        showDialog(dialog)
    }
    private fun enrollDialog() {
        val name = EditText(this).apply { hint = "Person's name"; filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
        val dialog = AlertDialog.Builder(this).setTitle("Enroll with consent")
            .setMessage("Ask permission first. Only one person should face the camera in good light. Stores a face template here, not a photo. This is not secure authentication.")
            .setView(name).setNegativeButton("Cancel", null).setPositiveButton("Consent & enroll", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = name.text.toString().trim()
                if (value.isEmpty()) { name.error = "Enter a name"; return@setOnClickListener }
                try {
                    if (vision.enroll(value)) { screen.reply.text = "Enrolled $value on this phone."; dialog.dismiss() }
                    else name.error = "Need exactly one fresh, frontal face. Enable camera and try again."
                } catch (_: Exception) { name.error = "Could not store enrollment. Try again." }
            }
        }
        showDialog(dialog)
    }
    private fun showSettings() {
        val saved = controller.settings
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        val enabled = Switch(this).apply { text = "Allow online AI for unknown requests"; isChecked = saved.enabled }
        val endpoint = EditText(this).apply { hint = "https://your-backend.example"; setText(saved.endpoint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val token = EditText(this).apply { hint = "Backend device token (not provider API key)"; setText(saved.token); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        column.addView(enabled); column.addView(endpoint); column.addView(token)
        val dialog = AlertDialog.Builder(this).setTitle("Optional online AI")
            .setMessage("Only your typed or transcribed request goes to your backend and its AI provider. No camera images, face names or audio are sent. Charges may apply. Local commands never use AI.")
            .setView(column).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = endpoint.text.toString().trim().toHttpUrlOrNull()
                if (enabled.isChecked && (url == null || !url.isHttps || url.username.isNotEmpty() ||
                    url.password.isNotEmpty() || url.query != null || url.fragment != null)) {
                    endpoint.error = "Enter an HTTPS backend origin without credentials, query or fragment"; return@setOnClickListener
                }
                if (enabled.isChecked && (token.text.length < 32 || token.text.any { it <= ' ' || it > '~' })) {
                    token.error = "Use at least 32 printable ASCII characters, without spaces"; return@setOnClickListener
                }
                val next = CloudSettings(endpoint.text.toString().trim(), token.text.toString(), enabled.isChecked)
                try { store.save(next); controller.settingsChanged(next); updatePrivacy(); dialog.dismiss() }
                catch (_: Exception) { token.error = "Could not securely save settings. Try again." }
            }
        }
        showDialog(dialog)
    }
    private fun showDialog(dialog: AlertDialog) {
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }
    private fun updatePrivacy() {
        screen.privacy.text = "Local camera + voice · AI ${if (controller.settings.enabled) "on when needed" else "off"} · No servos connected"
    }
}
