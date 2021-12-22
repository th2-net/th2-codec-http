package com.exactpro.th2.codec.http

import com.exactpro.th2.codec.http.HttpPipelineCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.google.protobuf.ByteString
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

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
        val message = RawMessage.newBuilder().apply {
            parentEventIdBuilder.id = eventID
            metadataBuilder.protocol = PROTOCOL
            metadataBuilder.idBuilder.direction = Direction.SECOND
            body = ByteString.copyFrom(request.toByteArray())
        }

        val messageGroup = MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setRawMessage(message).build()).build()

        val decodedEventID = codec.decode(messageGroup).getMessages(0).message.parentEventId

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
        val message = RawMessage.newBuilder().apply {
            parentEventIdBuilder.id = eventID
            metadataBuilder.protocol = PROTOCOL
            metadataBuilder.idBuilder.direction = Direction.FIRST
            body = ByteString.copyFrom(response.toByteArray())
        }

        val messageGroup = MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setRawMessage(message).build()).build()

        val decodedEventID = codec.decode(messageGroup).getMessages(0).message.parentEventId

        assertEquals(eventID, decodedEventID.id)
    }

}