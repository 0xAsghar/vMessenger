package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.data.network.ContactRequestRetryStore
import ir.vmessenger.data.network.DataStoreContactRequestRetryStore
import javax.inject.Singleton

/** The contact-request retry budget's disk, so its 48-attempt cap survives a restart. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContactRetryModule {
    @Binds
    @Singleton
    abstract fun bindContactRequestRetryStore(impl: DataStoreContactRequestRetryStore): ContactRequestRetryStore
}
