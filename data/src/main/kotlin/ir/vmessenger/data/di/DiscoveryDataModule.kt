package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ir.vmessenger.data.discovery.DataStorePublishSequenceStore
import ir.vmessenger.data.network.DatabaseBootstrapProvider
import ir.vmessenger.data.network.PeerEndpointCacheImpl
import ir.vmessenger.data.network.RelayDirectoryImpl
import ir.vmessenger.network.bootstrap.BootstrapProvider
import ir.vmessenger.network.discovery.PeerEndpointCache
import ir.vmessenger.network.discovery.PublishSequenceStore
import ir.vmessenger.network.messaging.RelayDirectory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoveryDataModule {
    @Binds
    @Singleton
    abstract fun bindPublishSequenceStore(impl: DataStorePublishSequenceStore): PublishSequenceStore

    @Binds
    @Singleton
    abstract fun bindPeerEndpointCache(impl: PeerEndpointCacheImpl): PeerEndpointCache

    @Binds
    @Singleton
    abstract fun bindRelayDirectory(impl: RelayDirectoryImpl): RelayDirectory

    @Binds
    @Singleton
    @IntoSet
    abstract fun bindDatabaseBootstrapProvider(impl: DatabaseBootstrapProvider): BootstrapProvider
}
