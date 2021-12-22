package com.exactpro.th2.codec.http

import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IPipelineCodecContext
import com.exactpro.th2.codec.api.IPipelineCodecFactory
import com.exactpro.th2.codec.api.IPipelineCodecSettings

class HttpPipelineCodecFactory : IPipelineCodecFactory {

    override val settingsClass: Class<out IPipelineCodecSettings> = HttpPipelineCodecSettings::class.java
    override val protocol: String = PROTOCOL

    override fun init(pipelineCodecContext: IPipelineCodecContext) {

    }

    override fun create(settings: IPipelineCodecSettings?): IPipelineCodec {
        return HttpPipelineCodec()
    }


    companion object {
        const val PROTOCOL = "http"
    }
}