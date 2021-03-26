/*
 * Copyright 2020-2020 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IPipelineCodecSettings
import com.exactpro.th2.common.grpc.AnyMessage.KindCase.MESSAGE
import com.exactpro.th2.common.grpc.AnyMessage.KindCase.RAW_MESSAGE
import com.exactpro.th2.common.grpc.Direction.FIRST
import com.exactpro.th2.common.grpc.Direction.SECOND
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.grpc.Value
import com.exactpro.th2.common.grpc.Value.KindCase.LIST_VALUE
import com.exactpro.th2.common.grpc.Value.KindCase.MESSAGE_VALUE
import com.exactpro.th2.common.grpc.Value.KindCase.SIMPLE_VALUE
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.plusAssign
import com.exactpro.th2.common.value.toValue
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite.Builder
import com.google.protobuf.Timestamp
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
    override val protocol: String = PROTOCOL

    override fun init(dictionary: IDictionaryStructure, settings: IPipelineCodecSettings?) {}

    override fun encode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList

        if (messages.isEmpty()) {
            return messageGroup
        }

        require(messages.size <= 2) { "Message group must contain at most 2 messages" }
        require(messages[0].kindCase == MESSAGE) { "First message must be a parsed message" }

        val message = messages[0].message

        require(message.metadata.protocol == protocol) { "Unsupported protocol: ${message.metadata.protocol}" }

        val body: RawMessage? = messages.getOrNull(1)?.run {
            require(kindCase == RAW_MESSAGE) { "Second message must be a raw message" }
            rawMessage
        }

        val messageFields = message.fieldsMap
        val builder = MessageGroup.newBuilder()

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

        builder += httpMessage.toByteArray().toRawMessage(
            metadata.timestamp,
            metadata.id,
            metadata.propertiesMap,
            body?.metadata?.propertiesMap
        )

        return builder.build()
    }

    override fun decode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList

        if (messages.isEmpty()) {
            return messageGroup
        }

        require(messages.size == 1) { "Message group must contain only 1 message" }
        require(messages[0].kindCase == RAW_MESSAGE) { "Message must be a raw message" }

        val message = messages[0].rawMessage
        val body = message.body.toByteArray().toString(UTF_8)
        val builder = MessageGroup.newBuilder()

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

    companion object {
        private const val PROTOCOL = "http"

        private const val REQUEST_MESSAGE = "Request"
        private const val RESPONSE_MESSAGE = "Response"
        private const val METHOD_FIELD = "method"
        private const val URI_FIELD = "uri"
        private const val STATUS_CODE_FIELD = "statusCode"
        private const val REASON_FIELD = "reason"
        private const val HEADERS_FIELD = "headers"
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

        private fun RawMessage.toBody() = BytesBody(body.toByteArray())

        private fun Writable.toByteArray() = ByteArrayOutputStream().apply(::writeTo).toByteArray()

        private inline operator fun <T : Builder> T.invoke(block: T.() -> Unit) = apply(block)

        private fun ByteArray.toRawMessage(
            timestamp: Timestamp,
            messageId: MessageID,
            metadataProperties: Map<String, String>,
            additionalMetadataProperties: Map<String, String>? = null,
            subsequence: Iterable<Int> = messageId.subsequenceList.dropLast(1)
        ): RawMessage = RawMessage.newBuilder().apply {
            this.body = ByteString.copyFrom(this@toRawMessage)
            this.metadataBuilder {
                putAllProperties(metadataProperties)
                additionalMetadataProperties?.run(::putAllProperties)
                this.timestamp = timestamp
                this.idBuilder.mergeFrom(messageId).apply {
                    this.addAllSubsequence(subsequence)
                }
            }
        }.build()

        private fun <T : StartLine> HttpMessage.convert(
            type: String,
            source: RawMessage,
            builder: MessageGroup.Builder,
            handleStartLine: T.(
                httpMessage: Message.Builder,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) {
            val metadata = source.metadata
            val metadataProperties = metadata.propertiesMap
            val additionalMetadataProperties = mutableMapOf<String, String>()
            val messageId = metadata.id
            val subsequence = messageId.subsequenceList

            builder += Message.newBuilder().apply {
                handleStartLine(startLine as T, this, additionalMetadataProperties)

                if (!headers.isEmpty) {
                    val headerList = arrayListOf<Value>()

                    headers.forEach { name, value ->
                        val headerMessage = Message.newBuilder()
                        headerMessage.addField(HEADER_NAME_FIELD, name)
                        headerMessage.addField(HEADER_VALUE_FIELD, value)
                        headerList += headerMessage.toValue()
                    }

                    addField(HEADERS_FIELD, headerList)
                }

                this.metadataBuilder {
                    putAllProperties(metadataProperties)
                    this.messageType = type
                    this.timestamp = metadata.timestamp
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
                    builder += it.toRawMessage(
                        metadata.timestamp,
                        messageId,
                        metadataProperties,
                        additionalMetadataProperties,
                        subsequence + 2
                    )
                }
        }

        private fun RawHttpRequest.convert(
            type: String,
            source: RawMessage,
            builder: MessageGroup.Builder,
            handleStartLine: RequestLine.(
                httpMessage: Message.Builder,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) = convert<RequestLine>(type, source, builder, handleStartLine)

        private fun RawHttpResponse<*>.convert(
            type: String,
            source: RawMessage,
            builder: MessageGroup.Builder,
            handleStartLine: StatusLine.(
                httpMessage: Message.Builder,
                metadataProperties: MutableMap<String, String>
            ) -> Unit
        ) = convert<StatusLine>(type, source, builder, handleStartLine)
    }
}
