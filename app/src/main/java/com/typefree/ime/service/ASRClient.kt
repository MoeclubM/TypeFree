package com.typefree.ime.service

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.typefree.ime.data.AsrConfig
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
import java.util.Locale
import java.util.concurrent.TimeUnit

class ASRClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var nativeSpeechRecognizer: SpeechRecognizer? = null

    interface ASRListener {
        fun onStartListening()
        fun onResult(text: String)
        fun onError(error: String)
    }

    fun startLocalSpeech(listener: ASRListener) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("Native Speech Recognition not available on this device")
            return
        }

        nativeSpeechRecognizer?.destroy()
        nativeSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listener.onStartListening()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown speech error: $error"
                    }
                    listener.onError(errorMessage)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        listener.onResult(matches[0])
                    } else {
                        listener.onError("No results matched")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            nativeSpeechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            listener.onError("Failed to start speech recognition: ${e.message}")
        }
    }

    fun stopLocalSpeech() {
        nativeSpeechRecognizer?.stopListening()
    }

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

    suspend fun transcribeApi(config: AsrConfig, file: File): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null

        val url = if (config.baseUrl.endsWith("/audio/transcriptions")) config.baseUrl
        else "${config.baseUrl.trimEnd('/')}/audio/transcriptions"

        val requestFile = file.asRequestBody("audio/m4a".toMediaType())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, requestFile)
            .addFormDataPart("model", config.model)
            .addFormDataPart("language", config.language)
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        if (config.apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
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
        nativeSpeechRecognizer?.destroy()
        nativeSpeechRecognizer = null
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
