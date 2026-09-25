package ir.vmessenger.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.nodesetup.InstallerBundle
import ir.vmessenger.core.nodesetup.NodeSetupSession
import ir.vmessenger.data.nodesetup.AssetInstallerBundle
import ir.vmessenger.data.nodesetup.NodeSetupController

@Module
@InstallIn(SingletonComponent::class)
abstract class NodeSetupModule {
    @Binds
    abstract fun bindNodeSetupSession(impl: NodeSetupController): NodeSetupSession

    @Binds
    abstract fun bindInstallerBundle(impl: AssetInstallerBundle): InstallerBundle
}
