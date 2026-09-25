package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.data.backup.RoomTransactionRunner
import ir.vmessenger.data.backup.TransactionRunner
import ir.vmessenger.data.network.ContactRequestService
import ir.vmessenger.data.network.NetworkCoordinator
import ir.vmessenger.data.network.NetworkNodeRepository
import ir.vmessenger.data.network.OutboxDispatcher
import ir.vmessenger.data.network.OutboxWaker
import ir.vmessenger.data.network.P2PSessionHooks
import ir.vmessenger.data.repository.ContactRepositoryImpl
import ir.vmessenger.data.repository.ContactRequestRepositoryImpl
import ir.vmessenger.data.repository.ConversationDraftStore
import ir.vmessenger.data.repository.ConversationRepositoryImpl
import ir.vmessenger.data.repository.DataStoreConversationDraftStore
import ir.vmessenger.data.repository.DiscoveryRepositoryImpl
import ir.vmessenger.data.repository.GroupRepositoryImpl
import ir.vmessenger.data.repository.IdentityBackupRepositoryImpl
import ir.vmessenger.data.repository.IdentityRepositoryImpl
import ir.vmessenger.data.repository.LocationAccessRepositoryImpl
import ir.vmessenger.data.repository.LocationRepositoryImpl
import ir.vmessenger.data.repository.PairingRepositoryImpl
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.ContactRequestRepository
import ir.vmessenger.domain.repository.ContactRequestSender
import ir.vmessenger.domain.repository.ConversationRepository
import ir.vmessenger.domain.repository.DiscoveryRepository
import ir.vmessenger.domain.repository.GroupRepository
import ir.vmessenger.domain.repository.IdentityBackupRepository
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.repository.LocationAccessRepository
import ir.vmessenger.domain.repository.LocationRepository
import ir.vmessenger.domain.repository.NodeManagementRepository
import ir.vmessenger.domain.repository.PairingRepository
import ir.vmessenger.domain.repository.RelayControl
import ir.vmessenger.network.messaging.SessionPostHandshakeHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindIdentityRepository(impl: IdentityRepositoryImpl): IdentityRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: GroupRepositoryImpl): GroupRepository

    @Binds
    @Singleton
    abstract fun bindIdentityBackupRepository(impl: IdentityBackupRepositoryImpl): IdentityBackupRepository

    @Binds
    @Singleton
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds
    @Singleton
    abstract fun bindContactRepository(impl: ContactRepositoryImpl): ContactRepository

    @Binds
    @Singleton
    abstract fun bindContactRequestSender(impl: ContactRequestService): ContactRequestSender

    @Binds
    @Singleton
    abstract fun bindContactRequestRepository(impl: ContactRequestRepositoryImpl): ContactRequestRepository

    @Binds
    @Singleton
    abstract fun bindLocationAccessRepository(impl: LocationAccessRepositoryImpl): LocationAccessRepository

    @Binds
    @Singleton
    abstract fun bindPairingRepository(impl: PairingRepositoryImpl): PairingRepository

    @Binds
    @Singleton
    abstract fun bindDiscoveryRepository(impl: DiscoveryRepositoryImpl): DiscoveryRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindConversationDraftStore(impl: DataStoreConversationDraftStore): ConversationDraftStore

    @Binds
    @Singleton
    abstract fun bindOutboxWaker(impl: OutboxDispatcher): OutboxWaker

    @Binds
    @Singleton
    abstract fun bindLocationRepository(impl: LocationRepositoryImpl): LocationRepository

    @Binds
    @Singleton
    abstract fun bindNodeManagementRepository(impl: NetworkNodeRepository): NodeManagementRepository

    @Binds
    abstract fun bindRelayControl(impl: NetworkCoordinator): RelayControl

    @Binds
    @Singleton
    abstract fun bindSessionPostHandshakeHandler(impl: P2PSessionHooks): SessionPostHandshakeHandler
}

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
