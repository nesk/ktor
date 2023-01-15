/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.http.content.*
import io.ktor.io.*
import io.ktor.io.jvm.javaio.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import java.io.*

internal actual suspend fun PipelineContext<Any, ApplicationCall>.defaultPlatformTransformations(
    query: Any
): Any? {
    val channel = query as? ByteReadChannel ?: return null

    return when (call.receiveType.type) {
        InputStream::class -> receiveGuardedInputStream(channel)
        MultiPartData::class -> multiPartData(channel)
        else -> null
    }
}

@OptIn(InternalAPI::class)
internal actual fun PipelineContext<*, ApplicationCall>.multiPartData(channel: ByteReadChannel): MultiPartData {
    val contentType = call.request.header(HttpHeaders.ContentType)
        ?: throw IllegalStateException("Content-Type header is required for multipart processing")

    return CIOMultipartDataBase(
        coroutineContext + Dispatchers.Unconfined,
        channel,
        contentType
    )
}

private fun receiveGuardedInputStream(channel: ByteReadChannel): InputStream {
    return channel.toInputStream()
}
