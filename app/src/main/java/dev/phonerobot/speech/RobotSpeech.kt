package dev.phonerobot.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Tap-to-talk only. Audio goes directly to Vosk in memory, never to a network recognizer. */
class RobotSpeech(
    context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onSpeaking: (Boolean) -> Unit,
    private val onListening: (Boolean) -> Unit = {},
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "robot-speech") }
    private val closed = AtomicBoolean(false)
    // Model is exclusively accessed by the worker. Other state is main-thread confined.
    private var model: Model? = null
    private var preparing = false
    private var modelReady = false
    private var capture: Capture? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var listening = false
    private var pendingSpeech = false
    private var generation = 0L
    private var utteranceId: String? = null

    fun prepare() = onMain {
        prepareVoice()
        if (preparing || modelReady) return@onMain
        preparing = true
        onStatus("Loading offline English speech model…")
        worker.execute {
            try {
                val directory = SpeechModelStore.unpack(app) { closed.get() }
                if (closed.get()) return@execute
                model = Model(directory.absolutePath)
                onMain {
                    preparing = false
                    modelReady = true
                    onStatus("Offline recognition ready. Tap Listen to speak.")
                }
            } catch (error: Exception) {
                reportPreparationFailure(error)
            } catch (error: LinkageError) {
                reportPreparationFailure(error)
            }
        }
    }

    fun listen() = onMain {
        when {
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                onStatus("Microphone permission is required. Allow it, then tap Listen again.")
            speaking || pendingSpeech -> onStatus("Wait for the robot to finish speaking, then tap Listen.")
            !modelReady -> onStatus("Offline speech model is not ready. Wait for loading or check the setup error.")
            capture != null -> onStatus("Already listening. Say one sentence or tap Stop.")
            else -> startCapture()
        }
    }

    /** Cancels this tap, discarding even a result already queued for delivery. */
    fun stopListening() = onMain {
        cancelCapture()
    }

    fun stopSpeaking() = onMain {
        generation += 1
        pendingSpeech = false
        utteranceId = null
        tts?.stop()
        if (speaking) {
            speaking = false
            onSpeaking(false)
        }
    }

    fun speak(text: String) = onMain {
        cancelCapture()
        val message = text.trim()
        if (message.isEmpty()) return@onMain
        val engine = tts
        if (!ttsReady || engine == null) {
            onStatus("Offline English voice unavailable. Install an offline English voice in Android text-to-speech settings.")
            return@onMain
        }
        generation += 1
        val request = generation
        pendingSpeech = true
        // FIFO barrier: the microphone and native recognizer are released before TTS starts.
        worker.execute {
            onMain {
                if (request != generation) return@onMain
                pendingSpeech = false
                speakOffline(engine, message, request)
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.post {
            generation += 1
            capture?.cancel()
            capture = null
            setListening(false)
            pendingSpeech = false
            utteranceId = null
            ttsReady = false
            tts?.stop()
            tts?.shutdown()
            tts = null
            if (speaking) {
                speaking = false
                onSpeaking(false)
            }
            // Do not interrupt native inference or free a Model while Recognizer uses it.
            worker.execute {
                model?.close()
                model = null
            }
            worker.shutdown()
        }
    }

    private fun startCapture() {
        val session = Capture()
        capture = session
        onStatus("Listening offline for one sentence (maximum 10 seconds)…")
        worker.execute {
            try {
                val loaded = model ?: error("Offline speech model is not loaded")
                val text = session.transcribe(loaded)
                onMain {
                    if (capture !== session) return@onMain
                    capture = null
                    setListening(false)
                    if (closed.get()) return@onMain
                    if (text.isBlank()) onStatus("No speech heard. Tap Listen to try again.")
                    else {
                        onStatus("Heard: $text")
                        if (closed.get()) return@onMain
                        onText(text)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Offline microphone recognition failed", error)
                onMain {
                    if (capture !== session) return@onMain
                    capture = null
                    setListening(false)
                    onStatus("Microphone recognition failed. Check permission and that another app is not using the microphone.")
                }
            }
        }
    }

    private fun cancelCapture() {
        val current = capture ?: return
        capture = null
        current.cancel()
        setListening(false)
        onStatus("Microphone stopped.")
    }

    private fun reportPreparationFailure(error: Throwable) {
        Log.e(TAG, "Offline model loading failed", error)
        onMain {
            preparing = false
            onStatus("Cannot load offline speech: ${error.message ?: "model initialization failed"}")
        }
    }

    private fun prepareVoice() {
        if (tts != null) return
        tts = TextToSpeech(app) { result ->
            // Always enqueue: some engines complete initialization before the constructor returns.
            main.post {
                if (closed.get()) return@post
                val engine = tts ?: return@post
                if (result != TextToSpeech.SUCCESS) {
                    engine.shutdown()
                    tts = null
                    onStatus("Android text-to-speech could not start. Install and enable an offline English voice.")
                    return@post
                }
                val voice = engine.voices.orEmpty()
                    .filter { it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired }
                    .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                    .sortedWith(compareBy({ it.locale != Locale.US }, { it.name }))
                    .firstOrNull()
                if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS || !hasOfflineVoice(engine)) {
                    engine.shutdown()
                    tts = null
                    onStatus("No installed offline English voice. Install one in Android text-to-speech settings; network voices are not used.")
                    return@post
                }
                ttsReady = true
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) = finishSpeaking(id, false)
                    @Deprecated("Android legacy callback")
                    override fun onError(id: String?) = finishSpeaking(id, true)
                    override fun onError(id: String?, errorCode: Int) = finishSpeaking(id, true)
                    override fun onStop(id: String?, interrupted: Boolean) = finishSpeaking(id, false)
                })
                onStatus("Offline English voice ready.")
            }
        }
    }

    private fun hasOfflineVoice(engine: TextToSpeech): Boolean {
        val voice = engine.voice ?: return false
        return !voice.isNetworkConnectionRequired && voice.locale.language == Locale.ENGLISH.language &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
    }

    private fun speakOffline(engine: TextToSpeech, text: String, request: Long) {
        if (!hasOfflineVoice(engine)) {
            ttsReady = false
            engine.stop()
            engine.shutdown()
            tts = null
            utteranceId = null
            if (speaking) {
                speaking = false
                onSpeaking(false)
            }
            onStatus("Offline English voice is no longer available. Network speech is disabled.")
            return
        }
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
            onStatus("Reply is too long for the installed speech engine.")
            return
        }
        val id = "robot-$request"
        utteranceId = id
        speaking = true
        onSpeaking(true)
        if (closed.get()) return
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finishSpeaking(id, true)
        }
    }

    private fun finishSpeaking(id: String?, failed: Boolean) = onMain {
        if (id != utteranceId || id == null) return@onMain
        utteranceId = null
        speaking = false
        onSpeaking(false)
        if (failed) onStatus("Offline voice could not speak this reply. Check Android text-to-speech settings.")
    }

    private fun onMain(action: () -> Unit) {
        if (closed.get()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            main.post { if (!closed.get()) action() }
        }
    }

    private fun setListening(active: Boolean) {
        if (listening == active) return
        listening = active
        onListening(active)
    }

    private inner class Capture {
        private val cancelled = AtomicBoolean(false)
        private val timedOut = AtomicBoolean(false)
        private var recorder: AudioRecord? = null
        private val deadline = Runnable {
            timedOut.set(true)
            stopMicrophone()
        }

        fun cancel() {
            cancelled.set(true)
            stopMicrophone()
        }

        @Synchronized
        private fun stopMicrophone() {
            val audio = recorder ?: return
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    audio.stop()
                    main.post {
                        if (capture === this@Capture) setListening(false)
                    }
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Microphone stop failed", error)
                }
            }
        }

        @SuppressLint("MissingPermission") // Permission checked on each listen; SecurityException is caught.
        @Synchronized
        private fun openMicrophone(): AudioRecord? {
            if (cancelled.get() || closed.get()) return null
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "16 kHz microphone input is unavailable" }
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, SAMPLE_RATE / 2),
            )
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Cannot start microphone" }
            main.postDelayed(deadline, MAX_LISTEN_MS)
            onMain {
                if (capture === this@Capture && !cancelled.get() && !timedOut.get()) setListening(true)
            }
            return audio
        }

        fun transcribe(model: Model): String {
            if (cancelled.get() || closed.get()) return ""
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            try {
                val audio = openMicrophone() ?: return ""
                val started = SystemClock.elapsedRealtime()
                val samples = ShortArray(SAMPLE_RATE / 10)
                var count = 0
                while (!cancelled.get() && !closed.get() && !timedOut.get() &&
                    SystemClock.elapsedRealtime() - started < MAX_LISTEN_MS && count < SAMPLE_RATE * 10
                ) {
                    val read = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    if (cancelled.get() || closed.get() || timedOut.get()) break
                    check(read >= 0) { "Microphone read failed ($read)" }
                    if (read == 0) {
                        Thread.sleep(10)
                        continue
                    }
                    count += read
                    if (recognizer.acceptWaveForm(samples, read)) {
                        val text = JSONObject(recognizer.result).optString("text").trim()
                        if (text.isNotEmpty()) return text
                    }
                }
                stopMicrophone()
                return if (cancelled.get() || closed.get()) "" else JSONObject(recognizer.finalResult).optString("text").trim()
            } finally {
                main.removeCallbacks(deadline)
                synchronized(this) {
                    stopMicrophone()
                    recorder?.release()
                    recorder = null
                }
                recognizer.close()
            }
        }
    }

    private companion object {
        const val TAG = "RobotSpeech"
        const val SAMPLE_RATE = 16_000
        const val MAX_LISTEN_MS = 10_000L
    }
}
