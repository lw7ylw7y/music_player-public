package app.songplayer.details

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** One request as the fake server saw it. */
class SeenRequest(val requestLine: String, val headers: Map<String, String>, val body: String)

/** A tiny HTTP server on the phone itself (debug builds allow cleartext to 127.0.0.1) that records requests and answers as told. */
class FakeLookupServer(var status: Int = 200, var body: String = "{\"status\":\"ok\",\"results\":[]}") : AutoCloseable {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    val seen = CopyOnWriteArrayList<SeenRequest>()
    val url: String get() = "http://127.0.0.1:${server.localPort}/v2/lookup"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { return@thread }
                thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }
    }

    private fun handle(socket: Socket) = socket.use {
        val input = it.getInputStream().buffered()
        fun line(): String {
            val sb = StringBuilder()
            while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
            return sb.toString()
        }
        val requestLine = line()
        val headers = LinkedHashMap<String, String>()
        while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(":").lowercase()] = l.substringAfter(":").trim() }
        val length = headers["content-length"]?.toInt() ?: 0
        val bytes = ByteArrayOutputStream()
        repeat(length) { bytes.write(input.read()) }
        seen += SeenRequest(requestLine, headers, bytes.toString("UTF-8"))
        val payload = body.toByteArray()
        it.getOutputStream().apply {
            write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(payload); flush()
        }
    }

    override fun close() = server.close()
}
