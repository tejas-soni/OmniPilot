package com.omnipilot.server

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JsonRpcClient(
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    private val LOG = Logger.getInstance(JsonRpcClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, (String?, String?) -> Unit>()
    private val notificationListeners = ConcurrentHashMap<String, MutableList<(JsonObject?) -> Unit>>()
    @Volatile
    private var isRunning = false
    private var readerThread: Thread? = null

    fun start() {
        isRunning = true
        readerThread = Thread({
            readLoop()
        }, "OmniPilot-RpcReader")
        readerThread?.isDaemon = true
        readerThread?.start()
    }

    fun stop() {
        isRunning = false
        readerThread?.interrupt()
    }

    fun onNotification(method: String, listener: (JsonObject?) -> Unit) {
        notificationListeners.computeIfAbsent(method) { CopyOnWriteArrayList() }.add(listener)
    }

    fun sendNotification(method: String, paramsJsonStr: String) {
        val payload = """{"jsonrpc":"2.0","method":"$method","params":$paramsJsonStr}"""
        sendRaw(payload)
    }

    fun sendRequest(method: String, paramsJsonStr: String, callback: (resultJson: String?, errorJson: String?) -> Unit) {
        val id = nextId.getAndIncrement()
        pendingRequests[id] = callback
        val payload = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$paramsJsonStr}"""
        sendRaw(payload)
    }

    private fun sendRaw(payload: String) {
        try {
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            val header = "Content-Length: ${bytes.size}\r\n\r\n"
            synchronized(outputStream) {
                outputStream.write(header.toByteArray(StandardCharsets.UTF_8))
                outputStream.write(bytes)
                outputStream.flush()
            }
        } catch (e: Exception) {
            LOG.error("Failed to send RPC payload", e)
        }
    }

    private fun readLoop() {
        try {
            val buffer = ByteArray(8192)
            var bytesRead = 0
            val byteBuffer = java.io.ByteArrayOutputStream()

            while (isRunning && inputStream.read(buffer).also { bytesRead = it } != -1) {
                byteBuffer.write(buffer, 0, bytesRead)
                var rawBytes = byteBuffer.toByteArray()

                while (true) {
                    val headerEnd = findSequence(rawBytes, "\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                    if (headerEnd == -1) break

                    val headerStr = String(rawBytes, 0, headerEnd, StandardCharsets.UTF_8)
                    val match = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(headerStr)
                    if (match == null) {
                        rawBytes = rawBytes.copyOfRange(headerEnd + 4, rawBytes.size)
                        byteBuffer.reset()
                        byteBuffer.write(rawBytes)
                        continue
                    }

                    val contentLength = match.groupValues[1].toInt()
                    val totalLength = headerEnd + 4 + contentLength

                    if (rawBytes.size < totalLength) break

                    val bodyBytes = rawBytes.copyOfRange(headerEnd + 4, totalLength)
                    val messageStr = String(bodyBytes, StandardCharsets.UTF_8)

                    rawBytes = rawBytes.copyOfRange(totalLength, rawBytes.size)
                    byteBuffer.reset()
                    byteBuffer.write(rawBytes)

                    handleMessage(messageStr)
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                LOG.error("RPC read loop error", e)
            }
        }
    }

    private fun handleMessage(messageStr: String) {
        try {
            val jsonObj = json.parseToJsonElement(messageStr).jsonObject

            // Check if response
            if (jsonObj.containsKey("id") && (jsonObj.containsKey("result") || jsonObj.containsKey("error")) && !jsonObj.containsKey("method")) {
                val id = jsonObj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (id != null) {
                    val cb = pendingRequests.remove(id)
                    val resultStr = jsonObj["result"]?.toString()
                    val errorStr = jsonObj["error"]?.toString()
                    cb?.invoke(resultStr, errorStr)
                }
                return
            }

            // Notification
            val method = jsonObj["method"]?.jsonPrimitive?.contentOrNull
            if (method != null) {
                val params = jsonObj["params"]?.jsonObject
                val listeners = notificationListeners[method]
                listeners?.forEach { it.invoke(params) }
            }
        } catch (e: Exception) {
            LOG.error("Failed to parse incoming RPC message: $messageStr", e)
        }
    }

    private fun findSequence(src: ByteArray, target: ByteArray): Int {
        for (i in 0..src.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (src[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}

// CopyOnWriteArrayList helper
private typealias CopyOnWriteArrayList<T> = java.util.concurrent.CopyOnWriteArrayList<T>
