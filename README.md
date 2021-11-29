# HTTP Codec v0.0.8

This microservice can encode and decode HTTP messages

## Configuration

This codec has no settings

## Protocol

This codec processes parsed messages with `http` protocol in metadata field

## Encoding

* input - `MessageGroup` with at most 2 messages:

    1. HTTP message - `Message` with `Request` or `Response` type (protocol = `http`)
    2. HTTP message body - `RawMessage` with a raw message body (if present)

* output - `MessageGroup` with a single `RawMessage` with encoded HTTP message

## Decoding

* input - `MessageGroup` with a single `RawMessage` with a raw HTTP request/response
* output - `MessageGroup` with at most 2 messages:

    1. HTTP message - `Message` with `Request` or `Response` type (protocol = `http`)
    2. HTTP message body - `RawMessage` with a raw message body (if present)

If decoded message was an HTTP request, message body metadata would contain `method` and `uri` properties with HTTP request method name and URI respectively

## Message types

* Request

|Field|Type|Description|
|:---:|:---:|:---:|
|method|String|HTTP method name (e.g. GET, POST, etc.)|
|uri|String|Request URI (e.g. /some/request/path?param1=value1&param2=value2...)|
|headers|List\<Header>|HTTP headers (e.g. Host, Content-Length, etc.)|

* Response

|Field|Type|Description|
|:---:|:---:|:---:|
|statusCode|String|HTTP status code (e.g. 200, 403, 500, etc)|
|reason|String|HTTP status reason (e.g. OK, Forbidden, Internal Server Error, etc.)|
|headers|List\<Header>|HTTP headers (e.g. Set-Cookie, Content-Length, etc.)|

* Header

|Field|Type|Description|
|:---:|:---:|:---:|
|name|String|HTTP header name|
|value|String|HTTP header value|

## Deployment via `infra-mgr`

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: codec-http
spec:
  image-name: ghcr.io/th2-net/th2-codec-http
  image-version: 0.0.8
  custom-config:
    codecSettings:
  type: th2-conn
  pins:
    # encoder
    - name: in_codec_encode
      connection-type: mq
      attributes:
        - encoder_in
        - subscribe
    - name: out_codec_encode
      connection-type: mq
      attributes:
        - encoder_out
        - publish
    # decoder
    - name: in_codec_decode
      connection-type: mq
      attributes:
        - decoder_in
        - subscribe
    - name: out_codec_decode
      connection-type: mq
      attributes:
        - decoder_out
        - publish
    # encoder general (technical)
    - name: in_codec_general_encode
      connection-type: mq
      attributes:
        - general_encoder_in
        - subscribe
    - name: out_codec_general_encode
      connection-type: mq
      attributes:
        - general_encoder_out
        - publish
    # decoder general (technical)
    - name: in_codec_general_decode
      connection-type: mq
      attributes:
        - general_decoder_in
        - subscribe
    - name: out_codec_general_decode
      connection-type: mq
      attributes:
        - general_decoder_out
        - publish
  extended-settings:
    service:
      enabled: false
```
