package dev.phonerobot.speech

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID

/** Copies APK assets privately; this class never downloads speech data at runtime. */
internal object SpeechModelStore {
    private const val ASSET_ROOT = "model-en-us"
    private val requiredFiles = listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst")

    fun unpack(context: Context, cancelled: () -> Boolean): File {
        val revision = try {
            context.assets.open("$ASSET_ROOT/uuid").bufferedReader().use { it.readText().trim() }
        } catch (error: IOException) {
            throw IOException("Bundled speech model missing. Run python3 tools/fetch_speech_model.py before building.", error)
        }
        require(revision.matches(Regex("[a-f0-9]{64}"))) { "Invalid bundled speech model revision" }
        val destination = File(context.noBackupFilesDir, "speech-model-$revision")
        if (complete(destination)) return destination
        val staging = File(context.noBackupFilesDir, "speech-copy-${UUID.randomUUID()}")
        try {
            copyAsset(context, ASSET_ROOT, staging, cancelled)
            if (cancelled()) throw InterruptedIOException("Speech preparation cancelled")
            check(complete(staging)) { "Bundled speech model is incomplete" }
            if (!staging.renameTo(destination) && !complete(destination)) {
                throw IOException("Cannot install the bundled speech model in app storage")
            }
            return destination
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun complete(root: File): Boolean = requiredFiles.all { File(root, it).length() > 0L }

    private fun copyAsset(context: Context, path: String, target: File, cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedIOException("Speech preparation cancelled")
        val children = context.assets.list(path).orEmpty()
        if (children.isNotEmpty()) {
            if (!target.mkdirs() && !target.isDirectory) throw IOException("Cannot create speech model directory")
            children.forEach { copyAsset(context, "$path/$it", File(target, it), cancelled) }
            return
        }
        context.assets.open(path).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) throw InterruptedIOException("Speech preparation cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
