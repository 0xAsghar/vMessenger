package ir.vmessenger.core.crypto.di

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {
    @Provides
    @Singleton
    fun provideLazySodium(): LazySodium = LazySodiumAndroid(SodiumAndroid())

    @Provides
    @Singleton
    fun provideCryptoEngine(lazySodium: LazySodium): CryptoEngine =
        LazysodiumCryptoEngine(lazySodium)
}
