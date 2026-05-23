package com.typefree.ime.service

import android.content.Context
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.typefree.ime.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ASRClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    fun startApiRecording(): Boolean {
        try {
            audioFile = File(context.cacheDir, "typefree_voice.m4a").apply {
                if (exists()) delete()
            }

            @Suppress("DEPRECATION")
            mediaRecorder = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(128000)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            return true
        } catch (e: Exception) {
            Log.e("ASRClient", "Failed to start MediaRecorder", e)
            return false
        }
    }

    fun stopApiRecording(): File? {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("ASRClient", "Failed to stop MediaRecorder", e)
        } finally {
            mediaRecorder = null
        }
        return audioFile
    }

    suspend fun transcribeApi(provider: ProviderConfig, modelName: String, language: String, file: File): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null

        return@withContext when {
            provider.type == "qwen_asr" || modelName.contains("qwen", ignoreCase = true) && modelName.contains("asr", ignoreCase = true) ->
                transcribeQwenAsr(provider, modelName, language, file)
            provider.type == "volcengine_asr" ->
                transcribeVolcengineAsr(provider, modelName, language, file)
            else -> transcribeOpenAiAudio(provider, modelName, language, file)
        }
    }

    private fun transcribeOpenAiAudio(provider: ProviderConfig, modelName: String, language: String, file: File): String? {
        val url = if (provider.baseUrl.endsWith("/audio/transcriptions")) provider.baseUrl
        else "${provider.baseUrl.trimEnd('/')}/audio/transcriptions"

        val requestFile = file.asRequestBody("audio/m4a".toMediaType())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, requestFile)
            .addFormDataPart("model", modelName)
            .addFormDataPart("language", language)
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        val request = requestBuilder.build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("ASRClient", "ASR upload failed: ${response.code} ${response.message}")
                    return null
                }
                val bodyString = response.body?.string() ?: return null
                try {
                    val jsonObj = JSONObject(bodyString)
                    jsonObj.optString("text", "")
                } catch (e: Exception) {
                    Log.e("ASRClient", "ASR response JSON parsing failed: $bodyString", e)
                    null
                }
            }
        } catch (e: IOException) {
            Log.e("ASRClient", "ASR API Call failed", e)
            null
        }
    }

    private fun transcribeQwenAsr(provider: ProviderConfig, modelName: String, language: String, file: File): String? {
        val url = if (provider.baseUrl.endsWith("/chat/completions")) provider.baseUrl
        else "${provider.baseUrl.trimEnd('/')}/chat/completions"
        val audioData = file.readAudioBase64DataUrl()
        val asrOptions = JSONObject()
            .put("enable_lid", language == "auto")
            .put("enable_itn", true)
        if (language != "auto") {
            asrOptions.put("language", language)
        }
        val body = JSONObject()
            .put("model", modelName.ifBlank { "qwen3-asr-flash" })
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_audio")
                                    .put(
                                        "input_audio",
                                        JSONObject()
                                            .put("data", audioData)
                                            .put("format", "m4a")
                                    )
                            )
                        )
                )
            )
            .put("asr_options", asrOptions)
            .toString()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        return executeJsonAsrRequest(requestBuilder.build()) { response ->
            response.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: response.optString("text", "")
        }
    }

    private fun transcribeVolcengineAsr(provider: ProviderConfig, modelName: String, language: String, file: File): String? {
        val url = provider.baseUrl.ifBlank {
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        }
        val credentials = provider.apiKey.split(":", limit = 2)
        val body = JSONObject()
            .put(
                "user",
                JSONObject().put("uid", "typefree")
            )
            .put(
                "audio",
                JSONObject()
                    .put("data", file.readAudioBase64())
                    .put("format", "m4a")
                    .put("codec", "aac")
                    .put("rate", 16000)
            )
            .put(
                "request",
                JSONObject()
                    .put("model_name", modelName.ifBlank { "volc.bigasr.auc_turbo" })
                    .put("enable_itn", true)
                    .put("language", language)
            )
            .toString()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
            .addHeader("X-Api-Resource-Id", modelName.ifBlank { "volc.bigasr.auc_turbo" })
        if (credentials.size == 2) {
            requestBuilder
                .addHeader("X-Api-App-Key", credentials[0])
                .addHeader("X-Api-Access-Key", credentials[1])
        } else if (provider.apiKey.isNotEmpty()) {
            requestBuilder
                .addHeader("Authorization", "Bearer ${provider.apiKey}")
                .addHeader("X-Api-Access-Key", provider.apiKey)
        }

        return executeJsonAsrRequest(requestBuilder.build()) { response ->
            response.optJSONObject("result")?.let { result ->
                result.optString("text", "")
                    .ifBlank { result.optJSONArray("utterances")?.joinText("text").orEmpty() }
            }.orEmpty()
                .ifBlank { response.optString("text", "") }
                .ifBlank { response.optJSONArray("utterances")?.joinText("text").orEmpty() }
        }
    }

    private fun executeJsonAsrRequest(request: Request, textExtractor: (JSONObject) -> String): String? {
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("ASRClient", "ASR request failed: ${response.code} ${response.message}")
                    return null
                }
                val bodyString = response.body?.string() ?: return null
                val text = textExtractor(JSONObject(bodyString)).trim()
                text.ifBlank { null }
            }
        } catch (e: Exception) {
            Log.e("ASRClient", "ASR request failed", e)
            null
        }
    }

    private fun File.readAudioBase64(): String {
        return Base64.encodeToString(readBytes(), Base64.NO_WRAP)
    }

    private fun File.readAudioBase64DataUrl(): String {
        return "data:audio/mp4;base64,${readAudioBase64()}"
    }

    private fun JSONArray.joinText(key: String): String {
        val builder = StringBuilder()
        for (index in 0 until length()) {
            val text = optJSONObject(index)?.optString(key).orEmpty()
            if (text.isNotBlank()) builder.append(text)
        }
        return builder.toString()
    }

    fun destroy() {
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
