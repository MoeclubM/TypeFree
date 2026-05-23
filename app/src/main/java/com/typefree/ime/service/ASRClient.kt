package com.typefree.ime.service

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.typefree.ime.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
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
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("ASRClient", "ASR upload failed: ${response.code} ${response.message}")
                    return@withContext null
                }
                val bodyString = response.body?.string() ?: return@withContext null
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

    fun destroy() {
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
