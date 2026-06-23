package ir.vmessenger.network.discovery.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ir.vmessenger.network.discovery.DhtDiscoveryProvider
import ir.vmessenger.network.discovery.DiscoveryProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiscoveryModule {
    @Provides
    @IntoSet
    @Singleton
    fun provideDhtDiscoveryProvider(provider: DhtDiscoveryProvider): DiscoveryProvider = provider
}
