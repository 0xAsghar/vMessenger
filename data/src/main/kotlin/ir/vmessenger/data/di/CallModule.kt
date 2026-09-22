package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.audio.ConcentusOpusCodecFactory
import ir.vmessenger.core.audio.OpusCodecFactory
import ir.vmessenger.data.call.CallMediaPort
import ir.vmessenger.data.call.CallMediaService
import javax.inject.Singleton

/**
 * What a call needs. Both bindings are interfaces on purpose: the codec so a native libopus can
 * replace Concentus without touching the call path, and the media port so [CallMediaPort]'s
 * coordinator can be tested without a socket or a microphone.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {
    @Binds
    @Singleton
    abstract fun bindCallMediaPort(impl: CallMediaService): CallMediaPort

    @Binds
    @Singleton
    abstract fun bindOpusCodecFactory(impl: ConcentusOpusCodecFactory): OpusCodecFactory
}
