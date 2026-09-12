package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.data.wipe.SecureWipeCoordinator
import ir.vmessenger.domain.repository.SecureWipeService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WipeModule {
    @Binds
    @Singleton
    abstract fun bindSecureWipeService(impl: SecureWipeCoordinator): SecureWipeService
}
