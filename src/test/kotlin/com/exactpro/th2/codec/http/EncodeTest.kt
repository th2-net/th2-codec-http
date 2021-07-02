package com.exactpro.th2.codec.http

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.messageType
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class EncodeTest {

    @Test
    fun `parent event id test - request`() {
        val eventID = "123"

        val codec = HttpPipelineCodec()
        val message = Message.newBuilder().apply {
            messageType = HttpPipelineCodec.REQUEST_MESSAGE
            addField(HttpPipelineCodec.URI_FIELD, "/test")
            addField(HttpPipelineCodec.METHOD_FIELD, "GET")
            parentEventIdBuilder.id = eventID
            metadataBuilder.protocol = HttpPipelineCodec.PROTOCOL
        }

        val messageGroup = MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setMessage(message).build()).build()

        val encodedEventID = codec.encode(messageGroup).getMessages(0).rawMessage.parentEventId

        assertEquals(eventID, encodedEventID.id)
    }

    @Test
    fun `parent event id test - response`() {
        val eventID = "123"

        val codec = HttpPipelineCodec()
        val message = Message.newBuilder().apply {
            messageType = HttpPipelineCodec.RESPONSE_MESSAGE
            addField(HttpPipelineCodec.STATUS_CODE_FIELD, "200")
            addField(HttpPipelineCodec.REASON_FIELD, "OK")
            parentEventIdBuilder.id = eventID
            metadataBuilder.protocol = HttpPipelineCodec.PROTOCOL
        }

        val messageGroup = MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setMessage(message).build()).build()

        val encodedEventID = codec.encode(messageGroup).getMessages(0).rawMessage.parentEventId

        assertEquals(eventID, encodedEventID.id)
    }

}