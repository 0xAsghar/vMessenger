package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.data.network.DefaultInboundRoutes
import ir.vmessenger.data.network.DefaultIncomingMessageNotifier
import ir.vmessenger.data.network.InboundRoutes
import ir.vmessenger.data.network.IncomingMessageNotifier
import ir.vmessenger.data.network.MessagingPort
import ir.vmessenger.data.network.MessagingServicePort
import ir.vmessenger.data.network.ProfileUpdateSender
import ir.vmessenger.data.repository.AndroidConversationNotificationCanceller
import ir.vmessenger.data.repository.ConversationNotificationCanceller
import ir.vmessenger.data.repository.PreferenceReadReceiptPolicy
import ir.vmessenger.data.repository.ReadReceiptPolicy
import ir.vmessenger.domain.repository.ProfileBroadcaster
import javax.inject.Singleton

/** Ports the inbound pipeline depends on; bound to their transport/Android-backed implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class InboundModule {
    @Binds
    @Singleton
    abstract fun bindMessagingPort(impl: MessagingServicePort): MessagingPort

    @Binds
    @Singleton
    abstract fun bindInboundRoutes(impl: DefaultInboundRoutes): InboundRoutes

    @Binds
    @Singleton
    abstract fun bindIncomingMessageNotifier(impl: DefaultIncomingMessageNotifier): IncomingMessageNotifier

    @Binds
    @Singleton
    abstract fun bindReadReceiptPolicy(impl: PreferenceReadReceiptPolicy): ReadReceiptPolicy

    @Binds
    @Singleton
    abstract fun bindProfileBroadcaster(impl: ProfileUpdateSender): ProfileBroadcaster

    @Binds
    @Singleton
    abstract fun bindConversationNotificationCanceller(
        impl: AndroidConversationNotificationCanceller,
    ): ConversationNotificationCanceller
}
