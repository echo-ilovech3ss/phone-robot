package dev.phonerobot.vision

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Stored in noBackupFilesDir: embeddings are never included in Android cloud backups. */
internal class FaceStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "enrolled-faces-v1.json"))
    private val matcher = FaceMatcher()
    private var entries: Map<String, FloatArray> = emptyMap()

    @Synchronized fun load() {
        val source = try {
            file.openRead() // Allow AtomicFile to recover an interrupted write before checking existence.
        } catch (error: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return
            throw error
        }
        val root = source.use {
            require(file.baseFile.length() <= 512_000) { "Face database is too large" }
            JSONObject(it.bufferedReader().readText())
        }
        require(root.getInt("version") == 1) { "Unsupported face database version" }
        val faces = root.getJSONArray("faces")
        require(faces.length() <= MAX_PEOPLE) { "Too many enrolled faces" }
        entries = (0 until faces.length()).associate { index ->
            val face = faces.getJSONObject(index)
            val name = face.getString("name")
            require(validName(name)) { "Invalid enrolled name" }
            val values = face.getJSONArray("embedding")
            require(values.length() == 192) { "Invalid face embedding size" }
            val embedding = FloatArray(192) { values.getDouble(it).toFloat() }
            name to requireNotNull(matcher.normalized(embedding)) { "Invalid face embedding" }
        }
    }

    @Synchronized fun snapshot(): Map<String, FloatArray> = entries

    @Synchronized fun put(name: String, embedding: FloatArray) {
        require(validName(name)) { "Use a name of 1–40 characters without control characters" }
        require(name in entries || entries.size < MAX_PEOPLE) { "Enrollment limit is $MAX_PEOPLE people" }
        val normalized = requireNotNull(matcher.normalized(embedding)) { "Invalid face embedding" }
        save(entries + (name to normalized))
    }

    @Synchronized fun forget(name: String) {
        save(entries - name)
    }

    private fun save(next: Map<String, FloatArray>) {
        val faces = JSONArray()
        next.forEach { (name, vector) ->
            faces.put(JSONObject().put("name", name).put("embedding", JSONArray(vector.toList())))
        }
        val bytes = JSONObject().put("version", 1).put("faces", faces).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        file.finishWrite(stream)
        // Never call failWrite after finishWrite: legacy Android would delete the committed base.
        check(file.openRead().use { it.readBytes() }.contentEquals(bytes)) {
            "Face storage commit could not be verified"
        }
        entries = next
    }

    companion object {
        private const val MAX_PEOPLE = 50
        fun validName(name: String): Boolean = name.isNotBlank() && name.length <= 40 &&
            name == name.trim() && name.none { it.isISOControl() }
    }
}
