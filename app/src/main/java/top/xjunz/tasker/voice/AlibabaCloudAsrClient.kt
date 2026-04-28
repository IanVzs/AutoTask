/*
 * Copyright (c) 2026 xjunz. All rights reserved.
 */

package top.xjunz.tasker.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import top.xjunz.tasker.Preferences
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

object AlibabaCloudAsrClient {

    private const val TOKEN_ENDPOINT = "https://nls-meta.cn-shanghai.aliyuncs.com/"
    private const val WEBSOCKET_ENDPOINT = "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1"
    private const val SAMPLE_RATE = 16000
    private const val AUDIO_CHUNK_BYTES = 3200
    private const val MAX_RECORD_MILLIS = 8000L
    private const val TOKEN_REFRESH_MARGIN_SECONDS = 60L

    suspend fun recognizeOnce(context: Context): String? = withContext(Dispatchers.IO) {
        if (!AsrServiceType.isAlibabaConfigured()) {
            throw AsrException("阿里云 ASR 配置不完整")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw AsrException("缺少麦克风权限")
        }
        val appKey = Preferences.speechRecognitionAppKey!!.trim()
        val token = getValidToken()
        var recognizedText: String? = null
        HttpClient(CIO) {
            install(WebSockets)
        }.use { client ->
            client.webSocket(urlString = "$WEBSOCKET_ENDPOINT?token=${percentEncode(token)}") {
                val taskId = randomHex32()
                send(Frame.Text(transcriptionCommand(appKey, taskId, "StartTranscription")))
                waitForEvent("TranscriptionStarted")
                recognizedText = streamMicrophoneAudio(context, appKey, taskId)
            }
        }
        recognizedText
    }

    private suspend fun DefaultClientWebSocketSession.streamMicrophoneAudio(
        context: Context,
        appKey: String,
        taskId: String
    ): String? = coroutineScope {
        val shouldStopAudio = AtomicBoolean(false)
        var latestResult: String? = null
        val receiver = async {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val event = JSONObject(frame.readText())
                val header = event.optJSONObject("header") ?: continue
                val status = header.optInt("status", 20000000)
                if (status != 20000000) {
                    throw AsrException(header.optString("status_message", "阿里云识别失败"))
                }
                when (header.optString("name")) {
                    "TranscriptionResultChanged",
                    "SentenceEnd" -> {
                        latestResult = event.optJSONObject("payload")
                            ?.optString("result")
                            ?.takeIf { it.isNotBlank() }
                        if (header.optString("name") == "SentenceEnd") {
                            shouldStopAudio.set(true)
                        }
                    }

                    "TranscriptionCompleted" -> return@async latestResult
                }
            }
            latestResult
        }
        val sender = launch {
            recordAndSendAudio(context, shouldStopAudio)
            send(Frame.Text(transcriptionCommand(appKey, taskId, "StopTranscription")))
        }
        try {
            withTimeout(MAX_RECORD_MILLIS + 7000L) {
                receiver.await()
            }
        } finally {
            shouldStopAudio.set(true)
            sender.cancelAndJoin()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun DefaultClientWebSocketSession.recordAndSendAudio(
        context: Context,
        shouldStopAudio: AtomicBoolean
    ) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            throw AsrException("无法初始化麦克风")
        }
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBufferSize, AUDIO_CHUNK_BYTES * 2)
        )
        try {
            audioRecord.startRecording()
            val buffer = ByteArray(AUDIO_CHUNK_BYTES)
            val startedAt = System.currentTimeMillis()
            while (
                isActive &&
                !shouldStopAudio.get() &&
                System.currentTimeMillis() - startedAt < MAX_RECORD_MILLIS
            ) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    send(Frame.Binary(true, buffer.copyOf(read)))
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }

    private suspend fun DefaultClientWebSocketSession.waitForEvent(name: String) {
        withTimeout(5000L) {
            while (true) {
                val frame = incoming.receive()
                if (frame !is Frame.Text) continue
                val event = JSONObject(frame.readText())
                val header = event.optJSONObject("header") ?: continue
                val status = header.optInt("status", 20000000)
                if (status != 20000000) {
                    throw AsrException(header.optString("status_message", "阿里云识别启动失败"))
                }
                if (header.optString("name") == name) return@withTimeout
            }
        }
    }

    private fun transcriptionCommand(appKey: String, taskId: String, name: String): String {
        val header = JSONObject()
            .put("appkey", appKey)
            .put("message_id", randomHex32())
            .put("task_id", taskId)
            .put("namespace", "SpeechTranscriber")
            .put("name", name)
        return JSONObject().put("header", header).apply {
            if (name == "StartTranscription") {
                put(
                    "payload",
                    JSONObject()
                        .put("format", "pcm")
                        .put("sample_rate", SAMPLE_RATE)
                        .put("enable_intermediate_result", true)
                        .put("enable_punctuation_prediction", true)
                        .put("enable_inverse_text_normalization", true)
                        .put("max_sentence_silence", 800)
                )
            }
        }.toString()
    }

    private suspend fun getValidToken(): String {
        val cachedToken = Preferences.speechRecognitionToken
        val nowSeconds = System.currentTimeMillis() / 1000
        if (!cachedToken.isNullOrBlank() &&
            Preferences.speechRecognitionTokenExpireTime - TOKEN_REFRESH_MARGIN_SECONDS > nowSeconds
        ) {
            return cachedToken
        }

        val accessKeyId = Preferences.speechRecognitionAccessKeyId!!.trim()
        val accessKeySecret = Preferences.speechRecognitionAccessKeySecret!!.trim()
        val query = linkedMapOf(
            "AccessKeyId" to accessKeyId,
            "Action" to "CreateToken",
            "Format" to "JSON",
            "RegionId" to "cn-shanghai",
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureNonce" to UUID.randomUUID().toString(),
            "SignatureVersion" to "1.0",
            "Timestamp" to DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            "Version" to "2019-02-28"
        )
        val canonicalizedQuery = query.toSortedMap().entries.joinToString("&") {
            "${percentEncode(it.key)}=${percentEncode(it.value)}"
        }
        val stringToSign = "GET&${percentEncode("/")}&${percentEncode(canonicalizedQuery)}"
        val signature = sign(stringToSign, "$accessKeySecret&")
        val signedQuery = query.toMutableMap().apply {
            put("Signature", signature)
        }.toSortedMap().entries.joinToString("&") {
            "${percentEncode(it.key)}=${percentEncode(it.value)}"
        }

        return HttpClient(CIO).use { client ->
            val response = client.get("$TOKEN_ENDPOINT?$signedQuery").bodyAsText()
            val token = JSONObject(response).getJSONObject("Token")
            val id = token.getString("Id")
            val expireTime = token.getLong("ExpireTime")
            Preferences.speechRecognitionToken = id
            Preferences.speechRecognitionTokenExpireTime = expireTime
            id
        }
    }

    private fun sign(content: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        return Base64.encodeToString(
            mac.doFinal(content.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun randomHex32(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    class AsrException(message: String) : Exception(message)
}
