package ir.vmessenger.data.attachment

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.data.network.AttachmentBatchSender
import ir.vmessenger.data.network.MessagingBatchSender
import javax.inject.Singleton

/** Ports of the attachment pipeline, bound to the store and the messaging service. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AttachmentModule {
    @Binds
    @Singleton
    abstract fun bindAttachmentContentSource(impl: AttachmentStore): AttachmentContentSource

    @Binds
    @Singleton
    abstract fun bindAttachmentIncomingStore(impl: AttachmentStore): AttachmentIncomingStore

    @Binds
    @Singleton
    abstract fun bindAttachmentBatchSender(impl: MessagingBatchSender): AttachmentBatchSender
}
