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

import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.http.HttpPipelineCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.common.grpc.AnyMessage.KindCase.MESSAGE
import com.exactpro.th2.common.grpc.AnyMessage.KindCase.RAW_MESSAGE
import com.exactpro.th2.common.grpc.Direction.FIRST
import com.exactpro.th2.common.grpc.Direction.SECOND
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.Message as ProtoMessage
import com.exactpro.th2.common.grpc.MessageGroup as ProtoMessageGroup
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.grpc.RawMessage as ProtoRawMessage
import com.exactpro.th2.common.grpc.Value
import com.exactpro.th2.common.grpc.Value.KindCase.LIST_VALUE
import com.exactpro.th2.common.grpc.Value.KindCase.MESSAGE_VALUE
import com.exactpro.th2.common.grpc.Value.KindCase.SIMPLE_VALUE
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.plusAssign
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.*
import com.exactpro.th2.common.value.toValue
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite.Builder
import io.netty.buffer.Unpooled
import rawhttp.core.HttpMessage
import rawhttp.core.HttpVersion.HTTP_1_1
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine
import rawhttp.core.StartLine
import rawhttp.core.StatusLine
import rawhttp.core.Writable
import rawhttp.core.body.BodyReader
import rawhttp.core.body.BytesBody
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.text.Charsets.UTF_8

class HttpPipelineCodec : IPipelineCodec {
    override fun encode(messageGroup: ProtoMessageGroup): ProtoMessageGroup {
        val messages = messageGroup.messagesList

        if (messages.isEmpty()) {
            return messageGroup
        }

        require(messages.size <= 2) { "Message group must contain at most 2 messages" }
        require(messages[0].kindCase == MESSAGE) { "First message must be a parsed message" }

        val message = messages[0].message

        require(message.metadata.protocol == PROTOCOL) { "Unsupported protocol: ${message.metadata.protocol}" }

        val body: ProtoRawMessage? = messages.getOrNull(1)?.run {
            require(kindCase == RAW_MESSAGE) { "Second message must be a raw message" }
            rawMessage
        }

        val messageFields = message.fieldsMap
        val builder = ProtoMessageGroup.newBuilder()

        val httpMessage: HttpMessage = when (val messageType = message.metadata.messageType) {
            REQUEST_MESSAGE -> messageFields.run {
                requireKnownFields(REQUEST_FIELDS)

                val uri = getString(URI_FIELD)
                val method = getString(METHOD_FIELD)
                val headers = RawHttpHeaders.newBuilder()

                getList(HEADERS_FIELD)?.fillHeaders(headers)

                RawHttpRequest(
                    RequestLine(method, URI(uri), HTTP_1_1),
                    headers.build(),
                    null,
                    null
                ).run {
                    body?.toBody().run(::withBody) ?: this
                }
            }
            RESPONSE_MESSAGE -> messageFields.run {
                requireKnownFields(RESPONSE_FIELDS)

                val statusCode = getString(STATUS_CODE_FIELD).toIntOrNull() ?: error("$STATUS_CODE_FIELD is not a number")
                val reason = getString(REASON_FIELD)
                val headers = RawHttpHeaders.newBuilder()

                getList(HEADERS_FIELD)?.fillHeaders(headers)

                RawHttpResponse(
                    null,
                    null,
                    StatusLine(HTTP_1_1, statusCode, reason),
                    headers.build(),
                    null
                ).run {
                    body?.toBody().run(::withBody) ?: this
                }
            }
            else -> error("Unsupported message type: $messageType")
        }

        val metadata = message.metadata

        builder += httpMessage.toByteArray().toProtoRawMessage(
            metadata.id,
            metadata.propertiesMap,
            body?.metadata?.propertiesMap,
            eventID = message.parentEventId
        )

        return builder.build()
    }

    override fun encode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messages

        if (messages.isEmpty()) {
            return messageGroup
        }

        require(messages.size <= 2) { "Message group must contain at most 2 messages" }
        val message = messages[0] as? ParsedMessage ?: error("First message must be a parsed message")

        require(message.protocol == PROTOCOL) { "Unsupported protocol: ${message.protocol}" }

        val body: RawMessage? = messages.getOrNull(1)?.run {
            require(this is RawMessage) { "Second message must be a raw message" }
            this
        }

        val encodedMessages = mutableListOf<Message<*>>()

        val httpMessage: HttpMessage = when (val messageType = message.type) {
            REQUEST_MESSAGE -> message.body.run {
                requireKnownFields(REQUEST_FIELDS)

                val uri = get(URI_FIELD) as String
                val method = get(METHOD_FIELD) as String
                val headers = RawHttpHeaders.newBuilder().apply {
                    (get(HEADERS_FIELD) as? MutableMap<String, String>)?.fillHeaders(this)
                }

                RawHttpRequest(
                    RequestLine(method, URI(uri), HTTP_1_1),
                    headers.build(),
                    null,
                    null
                ).run {
                    body?.toBody().run(::withBody) ?: this
                }
            }
            RESPONSE_MESSAGE -> message.body.run {
                requireKnownFields(RESPONSE_FIELDS)

                val statusCode = (get(STATUS_CODE_FIELD) as String).toIntOrNull() ?: error("$STATUS_CODE_FIELD is not a number")
                val reason = get(REASON_FIELD) as String
                val headers = RawHttpHeaders.newBuilder().apply {
                    (get(HEADERS_FIELD) as? MutableMap<String, String>)?.fillHeaders(this)
                }

                RawHttpResponse(
                    null,
                    null,
                    StatusLine(HTTP_1_1, statusCode, reason),
                    headers.build(),
                    null
                ).run {
                    body?.toBody().run(::withBody) ?: this
                }
            }
            else -> error("Unsupported message type: $messageType")
        }

        encodedMessages += RawMessage(
            message.id,
            message.eventId,
            message.metadata,
            message.protocol,
            Unpooled.wrappedBuffer(httpMessage.toByteArray())
        )

        return MessageGroup(encodedMessages)
    }

    override fun decode(messageGroup: ProtoMessageGroup): ProtoMessageGroup {
        val messages = messageGroup.messagesList

        if (messages.isEmpty()) {
            return messageGroup
        }

        require(messages.size == 1) { "Message group must contain only 1 message" }
        require(messages[0].kindCase == RAW_MESSAGE) { "Message must be a raw message" }

        val message = messages[0].rawMessage
        val body = message.body.toByteArray().toString(UTF_8)
        val builder = ProtoMessageGroup.newBuilder()

        when (val direction = message.metadata.id.direction) {
            FIRST -> RAW_HTTP.parseResponse(body).convert(RESPONSE_MESSAGE, message, builder) { httpMessage, _ ->
                httpMessage.addField(STATUS_CODE_FIELD, statusCode)
                httpMessage.addField(REASON_FIELD, reason)
            }
            SECOND -> RAW_HTTP.parseRequest(body).convert(REQUEST_MESSAGE, message, builder) { httpMessage, metadataProperties ->
                httpMessage.addField(METHOD_FIELD, method)
                httpMessage.addField(URI_FIELD, uri)
                metadataProperties[METHOD_METADATA_PROPERTY] = method
                metadataProperties[URI_METADATA_PROPERTY] = uri.toString()
            }
            else -> error("Unsupported message direction: $direction")
        }

        return builder.build()
    }

    override fun decode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messages

        if (messages.isEmpty()) {
            return messageGroup
        }

        require(messages.size == 1) { "Message group must contain only 1 message" }

        val message = messages[0]
        require(message is RawMessage) { "Message must be a raw message" }

        val body = message.body.toByteArray().toString(UTF_8)
        val decodedMessages = mutableListOf<Message<*>>()

        when (val direction = message.id.direction) {
            Direction.INCOMING -> RAW_HTTP.parseResponse(body).convert(RESPONSE_MESSAGE, message, decodedMessages) { httpMessage, _ ->
                httpMessage[STATUS_CODE_FIELD] = statusCode
                httpMessage[REASON_FIELD] = reason
            }
            Direction.OUTGOING -> RAW_HTTP.parseRequest(body).convert(REQUEST_MESSAGE, message, decodedMessages) { httpMessage, metadataProperties ->
                httpMessage[METHOD_FIELD] = method
                httpMessage[URI_FIELD] = uri
                metadataProperties[METHOD_METADATA_PROPERTY] = method
                metadataProperties[URI_METADATA_PROPERTY] = uri.toString()
            }
            else -> error("Unsupported message direction: $direction")
        }

        return MessageGroup(decodedMessages)
    }

    companion object {
        const val REQUEST_MESSAGE = "Request"
        const val RESPONSE_MESSAGE = "Response"
        const val METHOD_FIELD = "method"
        const val URI_FIELD = "uri"
        const val STATUS_CODE_FIELD = "statusCode"
        const val REASON_FIELD = "reason"
        const val HEADERS_FIELD = "headers"
        private const val HEADER_NAME_FIELD = "name"
        private const val HEADER_VALUE_FIELD = "value"

        private const val METHOD_METADATA_PROPERTY = METHOD_FIELD
        private const val URI_METADATA_PROPERTY = URI_FIELD

        private val REQUEST_FIELDS = setOf(METHOD_FIELD, URI_FIELD, HEADERS_FIELD)
        private val RESPONSE_FIELDS = setOf(STATUS_CODE_FIELD, REASON_FIELD, HEADERS_FIELD)
        private val HEADER_FIELDS = setOf(HEADER_NAME_FIELD, HEADER_VALUE_FIELD)

        private val RAW_HTTP = RawHttp()

        private fun Map<String, *>.requireKnownFields(messageFields: Set<String>) = require(keys.all(messageFields::contains)) {
            "Message contains unknown fields: ${keys.filterNot(messageFields::contains)}"
        }

        private fun Map<String, Value>.getString(fieldName: String) = get(fieldName)?.run {
            require(kindCase == SIMPLE_VALUE) { "$fieldName is not a string" }
            require(!simpleValue.isNullOrBlank()) { "$fieldName is null or blank" }
            simpleValue!!
        } ?: error("$fieldName is not set")

        private fun Map<String, Value>.getList(fieldName: String) = get(fieldName)?.run {
            require(kindCase == LIST_VALUE) { "$fieldName is not a list" }
            listValue.valuesList
        }

        private val Value.message
            get() = run {
                require(kindCase == MESSAGE_VALUE) { "value is not a message" }
                messageValue
            }

        private fun List<Value>.fillHeaders(builder: RawHttpHeaders.Builder) = forEach { header ->
            header.message.fieldsMap.run {
                requireKnownFields(HEADER_FIELDS)
                builder.with(getString(HEADER_NAME_FIELD), getString(HEADER_VALUE_FIELD))
            }
        }

        private fun Map<String, String>.fillHeaders(builder: RawHttpHeaders.Builder) =
            forEach { (name, value) -> builder.with(name, value) }

        private fun ProtoRawMessage.toBody() = BytesBody(body.toByteArray())
        private fun RawMessage.toBody() = BytesBody(body.array())

        private fun Writable.toByteArray() = ByteArrayOutputStream().apply(::writeTo).toByteArray()

        private inline operator fun <T : Builder> T.invoke(block: T.() -> Unit) = apply(block)

        private fun ByteArray.toProtoRawMessage(
            messageId: MessageID,
            metadataProperties: Map<String, String>,
            additionalMetadataProperties: Map<String, String>? = null,
            subsequence: Iterable<Int> = messageId.subsequenceList.dropLast(1),
            eventID: EventID
        ): ProtoRawMessage = ProtoRawMessage.newBuilder().apply {
            this.body = ByteString.copyFrom(this@toProtoRawMessage)
            parentEventIdBuilder.mergeFrom(eventID)
            this.metadataBuilder {
                putAllProperties(metadataProperties)
                additionalMetadataProperties?.run(::putAllProperties)
                this.idBuilder.mergeFrom(messageId).apply {
                    this.addAllSubsequence(subsequence)
                }
            }
        }.build()

        private fun <T : StartLine> HttpMessage.convert(
            type: String,
            source: ProtoRawMessage,
            builder: ProtoMessageGroup.Builder,
            handleStartLine: T.(
                httpMessage: ProtoMessage.Builder,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) {
            val metadata = source.metadata
            val metadataProperties = metadata.propertiesMap
            val additionalMetadataProperties = mutableMapOf<String, String>()
            val messageId = metadata.id
            val subsequence = messageId.subsequenceList

            builder += ProtoMessage.newBuilder().apply {
                handleStartLine(startLine as T, this, additionalMetadataProperties)
                parentEventIdBuilder.mergeFrom(source.parentEventId)

                if (!headers.isEmpty) {
                    val headerList = arrayListOf<Value>()

                    headers.forEach { name, value ->
                        val headerMessage = ProtoMessage.newBuilder()
                        headerMessage.addField(HEADER_NAME_FIELD, name)
                        headerMessage.addField(HEADER_VALUE_FIELD, value)
                        headerList += headerMessage.toValue()
                    }

                    addField(HEADERS_FIELD, headerList)
                }

                this.metadataBuilder {
                    putAllProperties(metadataProperties)
                    this.messageType = type
                    this.protocol = PROTOCOL
                    this.idBuilder.mergeFrom(messageId).apply {
                        this.addAllSubsequence(subsequence)
                        this.addSubsequence(1)
                    }
                }
            }.build()

            body.map(BodyReader::decodeBody)
                .filter(ByteArray::isNotEmpty)
                .ifPresent {
                    builder += it.toProtoRawMessage(
                        messageId,
                        metadataProperties,
                        additionalMetadataProperties,
                        subsequence + 2,
                        source.parentEventId
                    )
                }
        }

        private fun <T : StartLine> HttpMessage.convert(
            type: String,
            source: RawMessage,
            resultMessages: MutableList<Message<*>>,
            handleStartLine: T.(
                httpMessageBody: MutableMap<String, Any>,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) {
            val additionalMetadataProperties = mutableMapOf<String, String>()
            val messageId = source.id.toBuilder().addSubsequence(1).build()
            val parsedBody = mutableMapOf<String, Any>()

            handleStartLine(startLine as T, parsedBody, additionalMetadataProperties)

            if (!headers.isEmpty) {
                parsedBody[HEADERS_FIELD] = mutableMapOf<String, String>().apply {
                    headers.forEach { name, value -> this[name] = value }
                }
            }

            resultMessages += ParsedMessage(
                messageId.toBuilder().addSubsequence(1).build(),
                source.eventId,
                type,
                source.metadata,
                PROTOCOL,
                parsedBody
            )

            body.map(BodyReader::decodeBody)
                .filter(ByteArray::isNotEmpty)
                .ifPresent {
                    resultMessages += RawMessage(
                        messageId.toBuilder().addSubsequence(2).build(),
                        source.eventId,
                        source.metadata,
                        body = Unpooled.wrappedBuffer(it)
                    )
                }
        }

        private fun RawHttpRequest.convert(
            type: String,
            source: ProtoRawMessage,
            builder: ProtoMessageGroup.Builder,
            handleStartLine: RequestLine.(
                httpMessage: ProtoMessage.Builder,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) = convert<RequestLine>(type, source, builder, handleStartLine)

        private fun RawHttpResponse<*>.convert(
            type: String,
            source: ProtoRawMessage,
            builder: ProtoMessageGroup.Builder,
            handleStartLine: StatusLine.(
                httpMessage: ProtoMessage.Builder,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) = convert<StatusLine>(type, source, builder, handleStartLine)

        private fun RawHttpRequest.convert(
            type: String,
            source: RawMessage,
            builder: MutableList<Message<*>>,
            handleStartLine: RequestLine.(
                httpMessage: MutableMap<String, Any>,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) = convert<RequestLine>(type, source, builder, handleStartLine)

        private fun RawHttpResponse<*>.convert(
            type: String,
            source: RawMessage,
            builder: MutableList<Message<*>>,
            handleStartLine: StatusLine.(
                httpMessage: MutableMap<String, Any>,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) = convert<StatusLine>(type, source, builder, handleStartLine)
    }
}