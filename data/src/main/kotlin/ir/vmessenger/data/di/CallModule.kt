package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.data.call.CallMediaPort
import ir.vmessenger.data.call.CallMediaService
import javax.inject.Singleton

/**
 * The audio path, behind its interface, so the coordinator that decides whether a call happens can
 * be tested without a socket or a microphone. The codec's own binding lives next to the codec, in
 * `ir.vmessenger.core.audio.di.AudioModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {
    @Binds
    @Singleton
    abstract fun bindCallMediaPort(impl: CallMediaService): CallMediaPort
}
