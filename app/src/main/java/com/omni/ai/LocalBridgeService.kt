package com.omni.ai

import android.app.*
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class LocalBridgeService : Service() {

    companion object {
        init {
            System.loadLibrary("omni_llama")
        }
        const val PORT = 5004
        const val CHANNEL_ID = "OmniAiBridgeChannel"
        const val PREFS_NAME = "omni_ai_prefs"
        const val KEY_MODEL_PATH = "gguf_model_path"
    }

    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): ByteArray

    @Volatile private var running = false
    @Volatile private var currentModelPath: String = defaultModelPath()
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    private fun defaultModelPath(): String {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "model.gguf").absolutePath
    }

    private fun loadSavedModelPath(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_MODEL_PATH, null)?.trim()
        return if (!saved.isNullOrEmpty()) saved else defaultModelPath()
    }

    private fun saveAndLoadModelPath(newPath: String): Pair<Boolean, String> {
        val clean = newPath.trim().ifEmpty { defaultModelPath() }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_MODEL_PATH, clean).apply()
        currentModelPath = clean
        val file = File(clean)
        if (!file.exists() || !file.isFile) {
            return false to "Saved path ($clean), but file not found."
        }
        val loaded = nativeLoadModel(clean, 1024, 4)
        return if (loaded) {
            true to "Loaded model from $clean"
        } else {
            false to "Found $clean, but failed to load .gguf model."
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Termux Omni AI")
            .setContentText("Bridge listening on 127.0.0.1:$PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)

        currentModelPath = loadSavedModelPath()
        pool.execute {
            val f = File(currentModelPath)
            if (f.exists() && f.isFile) {
                nativeLoadModel(currentModelPath, 1024, 4)
            }
        }

        running = true
        pool.execute { startHttpServer() }
    }

    private fun startHttpServer() {
        try {
            serverSocket = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
            while (running) {
                val client = serverSocket?.accept() ?: break
                pool.execute { handleClient(client) }
            }
        } catch (_: Exception) { }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            if (method == "OPTIONS") {
                writeJsonResponse(s, "{}")
                return
            }

            val bodyChars = CharArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                if (read == -1) break
                totalRead += read
            }

            val jsonReq = try { JSONObject(String(bodyChars, 0, totalRead)) } catch (_: Exception) { JSONObject() }
            val action = jsonReq.optString("action", "")
            val resp = JSONObject()

            when (action) {
                "ping" -> {
                    val f = File(currentModelPath)
                    resp.put("status", "online")
                    resp.put("path", currentModelPath)
                    resp.put("message", if (f.exists()) "Online | GGUF at $currentModelPath" else "Online | Missing GGUF at $currentModelPath")
                }
                "set_model_path" -> {
                    val requestedPath = jsonReq.optString("path", "")
                    val (_, msg) = saveAndLoadModelPath(requestedPath)
                    resp.put("path", currentModelPath)
                    resp.put("message", msg)
                }
                "run_local_ai_shell" -> {
                    val prompt = jsonReq.optString("prompt", "")
                    val rawBytes = nativeGenerate(prompt, 256)
                    resp.put("category", "local_llm")
                    resp.put("stdout", String(rawBytes, StandardCharsets.UTF_8))
                }
                "read_file" -> {
                    val relPath = jsonReq.optString("path", "").trimStart('/')
                    val target = File(Environment.getExternalStorageDirectory(), relPath)
                    if (target.exists() && target.isFile) {
                        resp.put("content", target.readText(StandardCharsets.UTF_8))
                    } else {
                        resp.put("error", "File not found: $relPath")
                    }
                }
                "list_dir" -> {
                    val relPath = jsonReq.optString("path", "").trimStart('/')
                    var target = if (relPath.isEmpty()) Environment.getExternalStorageDirectory() else File(Environment.getExternalStorageDirectory(), relPath)
                    if (!target.exists()) target = Environment.getExternalStorageDirectory()
                    val arr = JSONArray()
                    target.list()?.forEach { arr.put(it) }
                    resp.put("path", target.absolutePath)
                    resp.put("files", arr)
                }
                else -> resp.put("error", "Unknown action")
            }

            writeJsonResponse(s, resp.toString())
        }
    }

    private fun writeJsonResponse(socket: Socket, json: String) {
        val bodyBytes = json.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: POST, OPTIONS\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Omni AI Bridge", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        running = false
        serverSocket?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

