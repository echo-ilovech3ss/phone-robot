package dev.phonerobot.cloud

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CloudSettings(val endpoint: String = "", val token: String = "", val enabled: Boolean = false)

class CloudClient {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private var active: Call? = null

    fun ask(settings: CloudSettings, message: String, complete: (Result<String>) -> Unit) {
        cancel()
        val url = settings.endpoint.toHttpUrlOrNull()
        if (!settings.enabled || url == null || !url.isHttps || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            settings.token.length < 32 || settings.token.length > 256 || !settings.token.all { it in '!'..'~' }) {
            complete(Result.failure(IOException("Configure an HTTPS backend and a device token of 32–256 ASCII characters.")))
            return
        }
        if (message.isBlank() || message.length > 1000) {
            complete(Result.failure(IOException("Use a message between 1 and 1000 characters.")))
            return
        }
        val request = runCatching {
            val body = JSONObject().put("message", message).toString().toRequestBody("application/json".toMediaType())
            Request.Builder().url(url.newBuilder().encodedPath("/v1/chat").build())
                .header("Authorization", "Bearer ${settings.token}").post(body).build()
        }.getOrElse { error ->
            complete(Result.failure(IOException("Invalid request parameters: ${error.message}")))
            return
        }
        val call = client.newCall(request)
        active = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = deliver(call,
                Result.failure(IOException("AI connection failed. Check internet and backend settings.")), complete)
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException(when (it.code) {
                            401 -> "The backend rejected the device token."
                            429 -> "AI limit reached. Please try later."
                            else -> "AI service unavailable (HTTP ${it.code})."
                        })
                        val source = it.body?.source() ?: throw IOException("AI returned no response.")
                        source.request(16385)
                        if (source.buffer.size > 16384) throw IOException("AI response exceeded the size limit.")
                        val reply = JSONObject(source.readUtf8()).optString("reply").trim()
                        if (reply.isEmpty() || reply.length > 2000) throw IOException("AI returned an invalid reply.")
                        reply
                    }
                }.recoverCatching { error ->
                    if (error is IOException) throw error
                    throw IOException("AI returned an unreadable response.")
                }
                deliver(call, result, complete)
            }
        })
    }
    private fun deliver(call: Call, result: Result<String>, complete: (Result<String>) -> Unit) {
        main.post { if (active === call && !call.isCanceled()) { active = null; complete(result) } }
    }
    fun cancel() { active?.cancel(); active = null }
    fun close() { cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
