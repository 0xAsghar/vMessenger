package ir.vmessenger.network.bootstrap.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ir.vmessenger.network.bootstrap.BootstrapProvider
import ir.vmessenger.network.bootstrap.DevBootstrapProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BootstrapModule {
    @Provides
    @IntoSet
    @Singleton
    fun provideDevBootstrapProvider(provider: DevBootstrapProvider): BootstrapProvider = provider
}
