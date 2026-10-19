package com.raiden.craffa.data.api

class MultipartFile(
    val fieldName: String,
    val fileName: String,
    val mime: String,
    val bytes: ByteArray,
)

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val multipart: MultipartFile? = null,
)

data class HttpResponse(val code: Int, val body: String)

/** Blocking HTTP. Implementations throw (IOException etc.) on network failure. */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}
