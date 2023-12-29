/*
 * Copyright 2020-2023 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.codec.http

import com.exactpro.th2.codec.api.impl.ReportingContext
import com.exactpro.th2.codec.http.HttpPipelineCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.common.grpc.AnyMessage as ProtoAnyMessage
import com.exactpro.th2.common.grpc.Direction as ProtoDirection
import com.exactpro.th2.common.grpc.MessageGroup as ProtoMessageGroup
import com.exactpro.th2.common.grpc.RawMessage as ProtoRawMessage
import com.google.protobuf.ByteString
import io.netty.buffer.Unpooled
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant

class DecodeTest {

    @Test
    fun `parent event id test - request`() {
        val eventID = "123"

        val request = """
            GET /hello.txt HTTP/1.1
            User-Agent: OpenSSL/0.9.7l
            Host: www.test.com
            Accept-Language: en, mi
        """.trimIndent()


        val codec = HttpPipelineCodec()
        val message = ProtoRawMessage.newBuilder().apply {
            parentEventIdBuilder.id = eventID
            metadataBuilder.protocol = PROTOCOL
            metadataBuilder.idBuilder.direction = ProtoDirection.SECOND
            body = ByteString.copyFrom(request.toByteArray())
        }

        val messageGroup = ProtoMessageGroup.newBuilder().addMessages(ProtoAnyMessage.newBuilder().setRawMessage(message).build()).build()

        val decodedEventID = codec.decode(messageGroup, ReportingContext()).getMessages(0).message.parentEventId

        assertEquals(eventID, decodedEventID.id)
    }

    @Test
    fun `parent event id test - response`() {
        val eventID = "123"

        val response = """
            HTTP/1.1 200 OK
            Content-Type: text/plain
            Content-Length: 0
        """.trimIndent()


        val codec = HttpPipelineCodec()
        val message = ProtoRawMessage.newBuilder().apply {
            parentEventIdBuilder.id = eventID
            metadataBuilder.protocol = PROTOCOL
            metadataBuilder.idBuilder.direction = ProtoDirection.FIRST
            body = ByteString.copyFrom(response.toByteArray())
        }

        val messageGroup = ProtoMessageGroup.newBuilder().addMessages(ProtoAnyMessage.newBuilder().setRawMessage(message).build()).build()

        val decodedEventID = codec.decode(messageGroup, ReportingContext()).getMessages(0).message.parentEventId

        assertEquals(eventID, decodedEventID.id)
    }

    @Test
    fun `parent event id test - response (transport)`() {
        val eventID = "123"

        val response = """
            HTTP/1.1 200 OK
            Content-Type: text/plain
            Content-Length: 0
        """.trimIndent()


        val codec = HttpPipelineCodec()
        val message = RawMessage(
            id = MessageId("alias_01", Direction.INCOMING, 1, Instant.now()),
            eventId = EventId(eventID, "book_01", "scope_01", Instant.now()),
            protocol = PROTOCOL,
            body = Unpooled.wrappedBuffer(response.toByteArray())
        )

        val messageGroup = MessageGroup(listOf(message))

        val decodedGroup = codec.decode(messageGroup, ReportingContext())

        val decodedMessage = decodedGroup.messages[0] as ParsedMessage

        val decodedEventID = decodedMessage.eventId
        val decodedBody =  decodedMessage.body

        assertEquals(PROTOCOL, decodedMessage.protocol)
        assertEquals("Response", decodedMessage.type)
        assertEquals(3, decodedBody.size)
        assertEquals(200, decodedBody["statusCode"])
        assertEquals("OK", decodedBody["reason"])
        val decodedHeaders = decodedBody["headers"] as Map<String, String>
        assertEquals(2, decodedHeaders.size)
        assertEquals("text/plain", decodedHeaders["Content-Type"])
        assertEquals("0", decodedHeaders["Content-Length"])
        assertEquals(eventID, decodedEventID?.id)
    }

    @Test
    fun `parent event id test - request (transport)`() {
        val eventID = "123"

        val request = """
            GET /hello.txt HTTP/1.1
            User-Agent: OpenSSL/0.9.7l
            Host: www.test.com
            Accept-Language: en, mi
        """.trimIndent()


        val codec = HttpPipelineCodec()
        val message = RawMessage(
            id = MessageId("alias_01", Direction.OUTGOING, 1, Instant.now()),
            eventId = EventId(eventID, "book_01", "scope_01", Instant.now()),
            protocol = PROTOCOL,
            body = Unpooled.wrappedBuffer(request.toByteArray())
        )

        val messageGroup = MessageGroup(listOf(message))

        val decodedGroup = codec.decode(messageGroup, ReportingContext())

        assertEquals(1, decodedGroup.messages.size)

        val decodedMessage = decodedGroup.messages[0] as ParsedMessage

        val decodedEventID = decodedMessage.eventId
        val decodedBody =  decodedMessage.body

        assertEquals(PROTOCOL, decodedMessage.protocol)
        assertEquals("Request", decodedMessage.type)
        assertEquals("GET", decodedBody["method"])
        assertEquals(URI("http://www.test.com/hello.txt"), decodedBody["uri"])
        val decodedHeaders = decodedBody["headers"] as Map<String, String>
        assertEquals(3, decodedHeaders.size)
        assertEquals("OpenSSL/0.9.7l", decodedHeaders["User-Agent"])
        assertEquals("www.test.com", decodedHeaders["Host"])
        assertEquals("en, mi", decodedHeaders["Accept-Language"])

        assertEquals(eventID, decodedEventID?.id)
    }
}