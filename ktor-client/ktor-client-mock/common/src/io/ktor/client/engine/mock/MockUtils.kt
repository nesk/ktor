/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.mock

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.io.*
import io.ktor.io.charsets.*
import io.ktor.util.*
import io.ktor.util.date.*
import kotlinx.coroutines.*

@Suppress("KDocMissingDocumentation")
public suspend fun OutgoingContent.toByteArray(): ByteArray = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes()
    is OutgoingContent.ReadChannelContent -> readFrom().toByteArray()
    is OutgoingContent.WriteChannelContent -> {
        ByteReadChannel {
            writeTo(this)
        }.toByteArray()
    }

    else -> ByteArray(0)
}

@Suppress("KDocMissingDocumentation")
public suspend fun OutgoingContent.toByteReadPacket(): Packet = when (this) {
    is OutgoingContent.ByteArrayContent -> Packet(bytes())
    is OutgoingContent.ReadChannelContent -> readFrom().readRemaining()
    is OutgoingContent.WriteChannelContent -> {
        ByteReadChannel {
            writeTo(this)
        }.readRemaining()
    }

    else -> Packet.Empty
}

/**
 * Send error response.
 */
public fun MockRequestHandleScope.respondError(
    status: HttpStatusCode,
    content: String = status.description,
    headers: Headers = headersOf()
): HttpResponseData = respond(content, status, headers)

/**
 * Send ok response.
 */
public fun MockRequestHandleScope.respondOk(
    content: String = ""
): HttpResponseData = respond(content, HttpStatusCode.OK)

/**
 * Respond redirect with [location] in Location header.
 */
public fun MockRequestHandleScope.respondRedirect(
    location: String = ""
): HttpResponseData = respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, location))

/**
 * Send [HttpStatusCode.BadRequest] response.
 */
public fun MockRequestHandleScope.respondBadRequest(): HttpResponseData =
    respond("Bad Request", HttpStatusCode.BadRequest)

/**
 * Send response with specified string [content], [status] and [headers].
 */
public fun MockRequestHandleScope.respond(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    headers: Headers = headersOf()
): HttpResponseData =
    respond(ByteReadChannel(content.toByteArray(Charsets.UTF_8)), status, headers)

/**
 * Send response with specified bytes [content], [status] and [headers].
 */
public fun MockRequestHandleScope.respond(
    content: ByteArray,
    status: HttpStatusCode = HttpStatusCode.OK,
    headers: Headers = headersOf()
): HttpResponseData = respond(ByteReadChannel(content), status, headers)

/**
 * Send response with specified [ByteReadChannel] [content], [status] and [headers].
 */
public fun MockRequestHandleScope.respond(
    content: ByteReadChannel,
    status: HttpStatusCode = HttpStatusCode.OK,
    headers: Headers = headersOf()
): HttpResponseData = HttpResponseData(
    status,
    GMTDate(),
    headers,
    HttpProtocolVersion.HTTP_1_1,
    content,
    callContext
)
