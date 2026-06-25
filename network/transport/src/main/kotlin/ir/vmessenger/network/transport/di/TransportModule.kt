package ir.vmessenger.network.transport.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ir.vmessenger.network.transport.InternetTransport
import ir.vmessenger.network.transport.RelayTransport
import ir.vmessenger.network.transport.Transport
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {
    @Provides
    @IntoSet
    @Singleton
    fun provideInternetTransport(transport: InternetTransport): Transport = transport

    @Provides
    @IntoSet
    @Singleton
    fun provideRelayTransport(transport: RelayTransport): Transport = transport
}
