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

/**
 * Transports available to [ir.vmessenger.network.transport.TransportSelector].
 * UDP ([ir.vmessenger.network.transport.UdpTransport]) is deliberately not
 * bound: it cannot complete a handshake today (datagrams, no ordering) and NAT
 * traversal ships disabled in 1.0, so keeping it out of the set means no dial
 * ever burns time on it.
 */
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
