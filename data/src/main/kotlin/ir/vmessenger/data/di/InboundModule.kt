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
}
