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

import com.exactpro.th2.codec.http.HttpPipelineCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.common.grpc.AnyMessage as ProtoAnyMessage
import com.exactpro.th2.common.grpc.Message as ProtoMessage
import com.exactpro.th2.common.grpc.MessageGroup as ProtoMessageGroup
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.messageType
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class EncodeTest {

    @Test
    fun `parent event id test - request`() {
        val eventID = "123"

        val codec = HttpPipelineCodec()
        val message = ProtoMessage.newBuilder().apply {
            messageType = HttpPipelineCodec.REQUEST_MESSAGE
            addField(HttpPipelineCodec.URI_FIELD, "/test")
            addField(HttpPipelineCodec.METHOD_FIELD, "GET")
            parentEventIdBuilder.id = eventID
            metadataBuilder.protocol = PROTOCOL
        }

        val messageGroup = ProtoMessageGroup.newBuilder().addMessages(ProtoAnyMessage.newBuilder().setMessage(message).build()).build()

        val encodedEventID = codec.encode(messageGroup).getMessages(0).rawMessage.parentEventId

        assertEquals(eventID, encodedEventID.id)
    }

    @Test
    fun `parent event id test - response`() {
        val eventID = "123"

        val codec = HttpPipelineCodec()
        val message = ProtoMessage.newBuilder().apply {
            messageType = HttpPipelineCodec.RESPONSE_MESSAGE
            addField(HttpPipelineCodec.STATUS_CODE_FIELD, "200")
            addField(HttpPipelineCodec.REASON_FIELD, "OK")
            parentEventIdBuilder.id = eventID
            metadataBuilder.protocol = PROTOCOL
        }

        val messageGroup = ProtoMessageGroup.newBuilder().addMessages(ProtoAnyMessage.newBuilder().setMessage(message).build()).build()
        val encodedEventID = codec.encode(messageGroup).getMessages(0).rawMessage.parentEventId

        assertEquals(eventID, encodedEventID.id)
    }

    @Test
    fun `parent event id test - request (transport)`() {
        val eventID = "123"

        val codec = HttpPipelineCodec()
        val message = ParsedMessage(
            eventId = EventId(eventID, "book_1", "scope_1", Instant.now()),
            type = HttpPipelineCodec.REQUEST_MESSAGE,
            protocol = PROTOCOL,
            body = mapOf(
                HttpPipelineCodec.URI_FIELD to "/test",
                HttpPipelineCodec.METHOD_FIELD to "GET"
            )
        )

        val messageGroup = MessageGroup(listOf(message))
        val encodedGroup = codec.encode(messageGroup)
        val encodedMessage = encodedGroup.messages[0] as RawMessage
        val encodedEventID = encodedMessage.eventId

        assertEquals(1, encodedGroup.messages.size)
        assertEquals(PROTOCOL, encodedMessage.protocol)
        assertEquals(22, encodedMessage.body.readableBytes())
        assertEquals(eventID, encodedEventID?.id)
    }

    @Test
    fun `parent event id test - response (transport)`() {
        val eventID = "123"

        val codec = HttpPipelineCodec()
        val message = ParsedMessage(
            eventId = EventId(eventID, "book_1", "scope_1", Instant.now()),
            type = HttpPipelineCodec.RESPONSE_MESSAGE,
            protocol = PROTOCOL,
            body = mapOf(
                HttpPipelineCodec.STATUS_CODE_FIELD to "200",
                HttpPipelineCodec.REASON_FIELD to "OK"
            )
        )

        val messageGroup = MessageGroup(listOf(message))
        val encodedGroup = codec.encode(messageGroup)

        val encodedMessage = encodedGroup.messages[0] as RawMessage
        val encodedEventID = encodedMessage.eventId

        assertEquals(1, encodedGroup.messages.size)
        assertEquals(PROTOCOL, encodedMessage.protocol)
        assertEquals(19, encodedMessage.body.readableBytes())
        assertEquals(eventID, encodedEventID?.id)
    }
}