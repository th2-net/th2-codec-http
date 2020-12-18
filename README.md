# Inputs/outputs

## encode

* input - `MessageGroup` with at most 2 messages:

    1. HTTP message - `Message` with `Request` or `Response` type
    2. HTTP message body - `RawMessage` with a raw message body (if present)

* output - `MessageGroup` with a single `RawMessage` with encoded HTTP message

## decode

* input - `MessageGroup` with a single `RawMessage` with a raw HTTP request/response
* output - `MessageGroup` with at most 2 messages:

    1. HTTP message - `Message` with `Request` or `Response` type
    2. HTTP message body - `RawMessage` with a raw message body (if present)

If decoded message was an HTTP request, message body metadata would contain `method` and `uri` properties with HTTP request method name and URI respectively

# Message types

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