package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.data.attachment.AttachmentFileStore
import ir.vmessenger.data.attachment.AttachmentStore
import ir.vmessenger.data.network.AndroidLocationServiceControl
import ir.vmessenger.data.network.LocationServiceControl
import ir.vmessenger.data.network.MessagingSessionCloser
import ir.vmessenger.data.network.SessionCloser
import javax.inject.Singleton

/** Ports behind contact cleanup and location sharing, bound to their transport/Android-backed implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContactCleanupModule {
    @Binds
    @Singleton
    abstract fun bindSessionCloser(impl: MessagingSessionCloser): SessionCloser

    @Binds
    @Singleton
    abstract fun bindAttachmentFileStore(impl: AttachmentStore): AttachmentFileStore

    @Binds
    @Singleton
    abstract fun bindLocationServiceControl(impl: AndroidLocationServiceControl): LocationServiceControl
}
