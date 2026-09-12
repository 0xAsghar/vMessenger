package ir.vmessenger.network.dht.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.network.dht.Dht
import ir.vmessenger.network.dht.DhtParticipationPolicy
import ir.vmessenger.network.dht.DhtRpcClient
import ir.vmessenger.network.dht.DhtRpcSender
import ir.vmessenger.network.dht.EmbeddedDhtPolicy
import ir.vmessenger.network.dht.EndpointRecordSigner
import ir.vmessenger.network.dht.EndpointRecordVerifier
import ir.vmessenger.network.dht.MinimalDht
import ir.vmessenger.network.dht.StoreRateLimiter
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

    @Provides
    @Singleton
    fun provideDhtRpcSender(client: DhtRpcClient): DhtRpcSender = client

    @Provides
    @Singleton
    fun provideDhtParticipationPolicy(policy: EmbeddedDhtPolicy): DhtParticipationPolicy = policy

    @Provides
    @Singleton
    fun provideStoreRateLimiter(): StoreRateLimiter = StoreRateLimiter()
}
