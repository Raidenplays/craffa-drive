package com.raiden.craffa.data.api

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val multipart = request.multipart
        val raw = request.body
        val body = when {
            multipart != null -> MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    multipart.fieldName,
                    multipart.fileName,
                    multipart.bytes.toRequestBody(multipart.mime.toMediaTypeOrNull()),
                )
                .build()
            raw != null -> raw.toRequestBody(request.contentType?.toMediaTypeOrNull())
            request.method in BODY_METHODS -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder().url(request.url).method(request.method, body)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            return HttpResponse(resp.code, resp.body.string())
        }
    }

    private companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
