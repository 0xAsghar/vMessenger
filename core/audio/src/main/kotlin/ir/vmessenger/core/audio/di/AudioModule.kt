package ir.vmessenger.core.audio.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.audio.ConcentusOpusCodecFactory
import ir.vmessenger.core.audio.OpusCodecFactory
import javax.inject.Singleton

/**
 * The codec, behind its interface.
 *
 * Concentus is pure JVM, which is why it was chosen: a native libopus would mean a `.so` for each
 * of the four shipped ABIs and JNI inside the process that holds the keys. Swapping it later is a
 * change to this one binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {
    @Binds
    @Singleton
    abstract fun bindOpusCodecFactory(impl: ConcentusOpusCodecFactory): OpusCodecFactory
}
