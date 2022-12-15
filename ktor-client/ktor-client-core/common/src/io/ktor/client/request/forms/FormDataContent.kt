/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.request.forms

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.io.*
import io.ktor.io.charsets.*
import kotlin.random.*

private val RN_BYTES = "\r\n".toByteArray()

/**
 * [OutgoingContent] with for the `application/x-www-form-urlencoded` formatted request.
 *
 * Example: [Form parameters](https://ktor.io/docs/request.html#form_parameters).
 *
 * @param formData: data to send.
 */
public class FormDataContent(
    public val formData: Parameters
) : OutgoingContent.ByteArrayContent() {
    private val content = formData.formUrlEncode().toByteArray()

    override val contentLength: Long = content.size.toLong()
    override val contentType: ContentType = ContentType.Application.FormUrlEncoded.withCharset(Charsets.UTF_8)

    override fun bytes(): ByteArray = content
}

/**
 * [OutgoingContent] for a `multipart/form-data` formatted request.
 *
 * Example: [Upload a file](https://ktor.io/docs/request.html#upload_file).
 *
 * @param parts: form part data
 */
public class MultiPartFormDataContent(
    parts: List<PartData>,
    public val boundary: String = generateBoundary(),
    override val contentType: ContentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
) : OutgoingContent.WriteChannelContent() {
    private val BOUNDARY_BYTES = "--$boundary\r\n".toByteArray()
    private val LAST_BOUNDARY_BYTES = "--$boundary--\r\n".toByteArray()

    private val BODY_OVERHEAD_SIZE = LAST_BOUNDARY_BYTES.size
    private val PART_OVERHEAD_SIZE = RN_BYTES.size * 2 + BOUNDARY_BYTES.size

    private val rawParts: List<PreparedPart> = parts.map { part ->
        val headersBuilder = Packet()
        for ((key, values) in part.headers.entries()) {
            headersBuilder.writeString("$key: ${values.joinToString("; ")}")
            headersBuilder.writeByteArray(RN_BYTES)
        }

        val bodySize = part.headers[HttpHeaders.ContentLength]?.toLong()
        when (part) {
            is PartData.FileItem -> {
                val headers = headersBuilder.toByteArray()
                val size = bodySize?.plus(PART_OVERHEAD_SIZE)?.plus(headers.size)
                PreparedPart.ChannelPart(headers, part.provider, size)
            }
            is PartData.BinaryItem -> {
                val headers = headersBuilder.toByteArray()
                val size = bodySize?.plus(PART_OVERHEAD_SIZE)?.plus(headers.size)
                PreparedPart.ChannelPart(headers, { ByteReadChannel(part.provider()) }, size)
            }
            is PartData.FormItem -> {
                val bytes = buildPacket { writeString(part.value) }.toByteArray()
                val provider = {
                    ByteReadChannel {
                        writeString(part.value)
                    }
                }
                if (bodySize == null) {
                    headersBuilder.writeString("${HttpHeaders.ContentLength}: ${bytes.size}")
                    headersBuilder.writeByteArray(RN_BYTES)
                }

                val headers = headersBuilder.toByteArray()
                val size = bytes.size + PART_OVERHEAD_SIZE + headers.size
                PreparedPart.ChannelPart(headers, provider, size.toLong())
            }
            is PartData.BinaryChannelItem -> {
                val headers = headersBuilder.toByteArray()
                val size = bodySize?.plus(PART_OVERHEAD_SIZE)?.plus(headers.size)
                PreparedPart.ChannelPart(headers, part.provider, size)
            }
        }
    }

    override val contentLength: Long?

    init {
        var rawLength: Long? = 0
        for (part in rawParts) {
            val size = part.size
            if (size == null) {
                rawLength = null
                break
            }

            rawLength = rawLength?.plus(size)
        }

        if (rawLength != null) {
            rawLength += BODY_OVERHEAD_SIZE
        }

        contentLength = rawLength
    }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        try {
            for (part in rawParts) {
                channel.writeByteArray(BOUNDARY_BYTES)
                channel.writeByteArray(part.headers)
                channel.writeByteArray(RN_BYTES)

                when (part) {
                    is PreparedPart.ChannelPart -> {
                        part.provider().copyTo(channel)
                    }
                }

                channel.writeByteArray(RN_BYTES)
            }

            channel.writeByteArray(LAST_BOUNDARY_BYTES)
        } catch (cause: Throwable) {
            channel.close(cause)
        } finally {
            channel.close()
        }
    }
}

private fun generateBoundary(): String = buildString {
    repeat(32) {
        append(Random.nextInt().toString(16))
    }
}.take(70)

private sealed class PreparedPart(val headers: ByteArray, val size: Long?) {
    class ChannelPart(
        headers: ByteArray,
        val provider: () -> ByteReadChannel,
        size: Long?
    ) : PreparedPart(headers, size)
}
