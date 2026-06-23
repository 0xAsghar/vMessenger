package ir.vmessenger.network.bootstrap.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ir.vmessenger.network.bootstrap.BootstrapProvider
import ir.vmessenger.network.bootstrap.BuiltInBootstrapProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BootstrapModule {
    @Provides
    @IntoSet
    @Singleton
    fun provideBuiltInBootstrapProvider(provider: BuiltInBootstrapProvider): BootstrapProvider = provider
}
