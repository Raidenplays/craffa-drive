package com.raiden.craffa

import com.raiden.craffa.data.api.HttpRequest
import com.raiden.craffa.data.api.HttpResponse
import com.raiden.craffa.data.api.HttpTransport
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI

/**
 * Minimal plain-HTTP/1.1 transport for running the API client against a local
 * server in unit tests. Uses only java.net.Socket so it compiles against
 * android.jar (unit tests don't get java.net.http), and supports PATCH
 * (HttpURLConnection doesn't). Test-only: no TLS, no redirects, no keep-alive.
 */
class JvmTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val uri = URI.create(request.url)
        require(uri.scheme == "http") { "JvmTransport only speaks plain http" }
        val port = if (uri.port == -1) 80 else uri.port
        val path = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")

        var body: ByteArray? = request.body
        var contentType = request.contentType
        request.multipart?.let { f ->
            val boundary = "craffa" + System.nanoTime()
            val head = "--$boundary\r\nContent-Disposition: form-data; name=\"${f.fieldName}\"; filename=\"${f.fileName}\"\r\n" +
                "Content-Type: ${f.mime}\r\n\r\n"
            body = head.toByteArray() + f.bytes + "\r\n--$boundary--\r\n".toByteArray()
            contentType = "multipart/form-data; boundary=$boundary"
        }

        Socket(uri.host, port).use { socket ->
            socket.soTimeout = 15_000
            val head = StringBuilder()
            head.append("${request.method} $path HTTP/1.1\r\n")
            head.append("Host: ${uri.host}:$port\r\n")
            head.append("Connection: close\r\n")
            request.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            contentType?.let { head.append("Content-Type: $it\r\n") }
            val b = body
            if (b != null || request.method in setOf("POST", "PUT", "PATCH")) {
                head.append("Content-Length: ${b?.size ?: 0}\r\n")
            }
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            if (b != null) out.write(b)
            out.flush()

            val raw = socket.getInputStream().readBytes()
            val split = indexOf(raw, "\r\n\r\n".toByteArray())
            val headerText = String(raw, 0, split, Charsets.ISO_8859_1)
            var payload = raw.copyOfRange(split + 4, raw.size)
            val lines = headerText.split("\r\n")
            val code = lines[0].split(" ")[1].toInt()
            val chunked = lines.any { it.lowercase().startsWith("transfer-encoding:") && "chunked" in it.lowercase() }
            if (chunked) payload = dechunk(payload)
            return HttpResponse(code, String(payload, Charsets.UTF_8))
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        error("malformed HTTP response")
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < data.size) {
            val lineEnd = indexOf(data.copyOfRange(pos, data.size), "\r\n".toByteArray()) + pos
            val size = String(data, pos, lineEnd - pos).substringBefore(';').trim().toInt(16)
            if (size == 0) break
            out.write(data, lineEnd + 2, size)
            pos = lineEnd + 2 + size + 2
        }
        return out.toByteArray()
    }
}
