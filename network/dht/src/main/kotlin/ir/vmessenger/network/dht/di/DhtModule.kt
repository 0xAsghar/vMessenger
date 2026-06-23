package ir.vmessenger.network.dht.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.network.dht.Dht
import ir.vmessenger.network.dht.EndpointRecordSigner
import ir.vmessenger.network.dht.EndpointRecordVerifier
import ir.vmessenger.network.dht.MinimalDht
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DhtModule {
    @Provides
    @Singleton
    fun provideEndpointRecordVerifier(cryptoEngine: CryptoEngine): EndpointRecordVerifier =
        EndpointRecordVerifier(cryptoEngine)

    @Provides
    @Singleton
    fun provideEndpointRecordSigner(cryptoEngine: CryptoEngine): EndpointRecordSigner =
        EndpointRecordSigner(cryptoEngine)

    @Provides
    @Singleton
    fun provideDht(dht: MinimalDht): Dht = dht
}
