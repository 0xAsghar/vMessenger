package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.data.discovery.DataStorePublishSequenceStore
import ir.vmessenger.network.discovery.PublishSequenceStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoveryDataModule {
    @Binds
    @Singleton
    abstract fun bindPublishSequenceStore(impl: DataStorePublishSequenceStore): PublishSequenceStore
}
