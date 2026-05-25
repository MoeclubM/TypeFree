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
import java.io.ByteArrayOutputStream
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

    private data class AudioPayload(
        val mimeType: String,
        val dataUrlMimeType: String,
        val format: String,
        val volcCodec: String,
        val sampleRate: Int
    )

    private data class AsrApiResult(
        val success: Boolean,
        val text: String?,
        val message: String
    )

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
            provider.type == "openai_audio_asr" ->
                transcribeOpenAiAudio(provider, modelName, language, file)
            provider.type == "qwen_asr" || modelName.contains("qwen", ignoreCase = true) && modelName.contains("asr", ignoreCase = true) ->
                transcribeQwenAsr(provider, modelName, language, file)
            provider.type == "volcengine_asr" ->
                transcribeVolcengineAsr(provider, modelName, language, file)
            else -> transcribeOpenAiAudio(provider, modelName, language, file)
        }
    }

    suspend fun testAsrModel(provider: ProviderConfig, modelName: String, language: String): ModelTestResult = withContext(Dispatchers.IO) {
        if (modelName.isBlank()) {
            return@withContext ModelTestResult(false, "模型为空")
        }
        if (provider.baseUrl.isBlank()) {
            return@withContext ModelTestResult(false, "未配置 Base URL")
        }

        val testFile = createSilentWavFile()
        try {
            val result = when {
                provider.type == "openai_audio_asr" -> executeOpenAiAudioTranscription(provider, modelName, language, testFile)
                provider.type == "qwen_asr" || modelName.contains("qwen", ignoreCase = true) && modelName.contains("asr", ignoreCase = true) ->
                    executeQwenAsr(provider, modelName, language, testFile)
                provider.type == "volcengine_asr" -> executeVolcengineAsr(provider, modelName, language, testFile)
                else -> executeOpenAiAudioTranscription(provider, modelName, language, testFile)
            }
            if (result.success) {
                val textSuffix = result.text?.takeIf { it.isNotBlank() }?.let { "，返回: ${it.take(40)}" }.orEmpty()
                ModelTestResult(true, "ASR 测试通过: ${result.message}$textSuffix")
            } else {
                ModelTestResult(false, "ASR 测试失败: ${result.message}")
            }
        } finally {
            if (testFile.exists()) testFile.delete()
        }
    }

    private fun transcribeOpenAiAudio(provider: ProviderConfig, modelName: String, language: String, file: File): String? {
        val result = executeOpenAiAudioTranscription(provider, modelName, language, file)
        if (!result.success) {
            Log.e("ASRClient", "ASR upload failed: ${result.message}")
        }
        return result.text?.takeIf { it.isNotBlank() }
    }

    private fun executeOpenAiAudioTranscription(provider: ProviderConfig, modelName: String, language: String, file: File): AsrApiResult {
        val url = openAiEndpointUrl(provider.baseUrl, "audio/transcriptions")
        val payload = audioPayloadForFile(file)

        val requestFile = file.asRequestBody(payload.mimeType.toMediaType())

        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, requestFile)
            .addFormDataPart("model", modelName)
            .addFormDataPart("response_format", "json")
        if (language.isNotBlank() && language != "auto") {
            requestBodyBuilder.addFormDataPart("language", language)
        }
        val requestBody = requestBodyBuilder.build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        val request = requestBuilder.build()
        return try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return AsrApiResult(false, null, "HTTP ${response.code}: ${bodyString.compactForMessage().ifBlank { response.message }}")
                }
                try {
                    val jsonObj = JSONObject(bodyString)
                    AsrApiResult(true, jsonObj.optString("text", ""), "HTTP ${response.code} ${url}")
                } catch (e: Exception) {
                    Log.e("ASRClient", "ASR response JSON parsing failed: $bodyString", e)
                    AsrApiResult(false, null, "响应不是可解析 JSON: ${bodyString.compactForMessage()}")
                }
            }
        } catch (e: IOException) {
            Log.e("ASRClient", "ASR API Call failed", e)
            AsrApiResult(false, null, e.message ?: "网络请求失败")
        }
    }

    private fun transcribeQwenAsr(provider: ProviderConfig, modelName: String, language: String, file: File): String? {
        val result = executeQwenAsr(provider, modelName, language, file)
        if (!result.success) {
            Log.e("ASRClient", "Qwen ASR failed: ${result.message}")
        }
        return result.text?.takeIf { it.isNotBlank() }
    }

    private fun executeQwenAsr(provider: ProviderConfig, modelName: String, language: String, file: File): AsrApiResult {
        val url = openAiEndpointUrl(provider.baseUrl, "chat/completions")
        val payload = audioPayloadForFile(file)
        val audioData = file.readAudioBase64DataUrl(payload.dataUrlMimeType)
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
                                            .put("format", payload.format)
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
        val result = executeVolcengineAsr(provider, modelName, language, file)
        if (!result.success) {
            Log.e("ASRClient", "Volcengine ASR failed: ${result.message}")
        }
        return result.text?.takeIf { it.isNotBlank() }
    }

    private fun executeVolcengineAsr(provider: ProviderConfig, modelName: String, language: String, file: File): AsrApiResult {
        val url = provider.baseUrl.ifBlank {
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        }
        val credentials = provider.apiKey.split(":", limit = 2)
        val payload = audioPayloadForFile(file)
        val body = JSONObject()
            .put(
                "user",
                JSONObject().put("uid", "typefree")
            )
            .put(
                "audio",
                JSONObject()
                    .put("data", file.readAudioBase64())
                    .put("format", payload.format)
                    .put("codec", payload.volcCodec)
                    .put("rate", payload.sampleRate)
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

    private fun executeJsonAsrRequest(request: Request, textExtractor: (JSONObject) -> String): AsrApiResult {
        return try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return AsrApiResult(false, null, "HTTP ${response.code}: ${bodyString.compactForMessage().ifBlank { response.message }}")
                }
                val text = textExtractor(JSONObject(bodyString)).trim()
                AsrApiResult(true, text, "HTTP ${response.code} ${request.url}")
            }
        } catch (e: Exception) {
            Log.e("ASRClient", "ASR request failed", e)
            AsrApiResult(false, null, e.message ?: "网络请求失败")
        }
    }

    private fun File.readAudioBase64(): String {
        return Base64.encodeToString(readBytes(), Base64.NO_WRAP)
    }

    private fun File.readAudioBase64DataUrl(mimeType: String): String {
        return "data:$mimeType;base64,${readAudioBase64()}"
    }

    private fun audioPayloadForFile(file: File): AudioPayload {
        return when (file.extension.lowercase()) {
            "wav" -> AudioPayload(
                mimeType = "audio/wav",
                dataUrlMimeType = "audio/wav",
                format = "wav",
                volcCodec = "pcm",
                sampleRate = 16000
            )
            else -> AudioPayload(
                mimeType = "audio/m4a",
                dataUrlMimeType = "audio/mp4",
                format = "m4a",
                volcCodec = "aac",
                sampleRate = 16000
            )
        }
    }

    private fun createSilentWavFile(): File {
        return File(context.cacheDir, "typefree_asr_test.wav").apply {
            if (exists()) delete()
            writeBytes(silentWavBytes())
        }
    }

    private fun silentWavBytes(durationMs: Int = 1200, sampleRate: Int = 16000): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val sampleCount = sampleRate * durationMs / 1000
        val dataSize = sampleCount * channels * bytesPerSample
        return ByteArrayOutputStream(44 + dataSize).apply {
            writeAscii("RIFF")
            writeIntLe(36 + dataSize)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeIntLe(16)
            writeShortLe(1)
            writeShortLe(channels)
            writeIntLe(sampleRate)
            writeIntLe(sampleRate * channels * bytesPerSample)
            writeShortLe(channels * bytesPerSample)
            writeShortLe(bitsPerSample)
            writeAscii("data")
            writeIntLe(dataSize)
            write(ByteArray(dataSize))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun String.compactForMessage(limit: Int = 220): String {
        return replace(Regex("\\s+"), " ").trim().take(limit)
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
