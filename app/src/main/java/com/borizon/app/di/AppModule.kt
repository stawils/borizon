package com.borizon.app.di

import android.content.Context
import com.borizon.app.ai.inference.ModelManager
import com.borizon.app.data.BorizonSettingsSerializer
import com.borizon.app.data.PreferencesManager
import com.borizon.app.data.database.BorizonDatabase
import com.borizon.app.proto.BorizonSettings
import com.borizon.app.skills.SkillManager
import com.borizon.app.ai.tools.JavascriptBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BorizonDatabase {
        return BorizonDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun providePreferencesManager(@ApplicationContext context: Context): PreferencesManager {
        return PreferencesManager(context)
    }

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager {
        return ModelManager(context)
    }

    @Provides
    @Singleton
    fun provideAppLifecycleProvider(): AppLifecycleProvider {
        return AppLifecycleProviderImpl()
    }

    @Provides
    @Singleton
    fun provideSkillManager(@ApplicationContext context: Context): SkillManager {
        return SkillManager(context)
    }

    @Provides
    @Singleton
    fun provideJavascriptBridge(@ApplicationContext context: Context): JavascriptBridge {
        return JavascriptBridge(context)
    }
}
