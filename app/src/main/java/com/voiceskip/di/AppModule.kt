// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.di

import android.app.Application
import android.content.res.AssetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.SavedTranscriptionRepository
import com.voiceskip.data.repository.SavedTranscriptionRepositoryImpl
import com.voiceskip.data.repository.SettingsRepository
import com.voiceskip.data.repository.SettingsRepositoryImpl
import com.voiceskip.data.repository.TranscriptionRepository
import com.voiceskip.data.source.SavedTranscriptionDataSource
import com.voiceskip.data.source.SavedTranscriptionDataSourceImpl
import com.voiceskip.data.source.WhisperDataSource
import com.voiceskip.data.source.WhisperDataSourceImpl
import com.voiceskip.domain.ModelManager
import com.voiceskip.service.ServiceLauncher
import com.voiceskip.service.ServiceLauncherImpl
import com.voiceskip.ui.main.AudioPlaybackManager
import com.voiceskip.ui.main.FileManager
import com.voiceskip.util.AppLifecycleTracker
import com.voiceskip.util.AppLifecycleTrackerImpl
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideUserPreferences(application: Application): UserPreferences {
        return UserPreferences(application)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        userPreferences: UserPreferences
    ): SettingsRepository {
        return SettingsRepositoryImpl(userPreferences)
    }

    @Provides
    @Singleton
    fun provideFileManager(
        application: Application
    ): FileManager {
        return FileManager(
            modelsPath = File(application.filesDir, "models"),
            samplesPath = File(application.filesDir, "samples")
        )
    }

    @Provides
    @Singleton
    fun provideAudioPlaybackManager(): AudioPlaybackManager {
        return AudioPlaybackManager()
    }

    @Provides
    @Singleton
    fun provideModelManager(
        repository: TranscriptionRepository,
        userPreferences: UserPreferences,
        fileManager: FileManager
    ): ModelManager {
        return ModelManager(repository, userPreferences, fileManager)
    }

    @Provides
    @Singleton
    fun provideAssetManager(application: Application): AssetManager {
        return application.assets
    }

    @Provides
    @Singleton
    fun provideWhisperDataSource(): WhisperDataSource {
        return WhisperDataSourceImpl()
    }

    @Provides
    @Singleton
    fun provideSavedTranscriptionDataSource(
        application: Application
    ): SavedTranscriptionDataSource {
        return SavedTranscriptionDataSourceImpl(application)
    }

    @Provides
    @Singleton
    fun provideSavedTranscriptionRepository(
        dataSource: SavedTranscriptionDataSource
    ): SavedTranscriptionRepository {
        return SavedTranscriptionRepositoryImpl(dataSource)
    }

    @Provides
    @Singleton
    fun provideAppLifecycleTracker(
        impl: AppLifecycleTrackerImpl
    ): AppLifecycleTracker {
        return impl
    }

    @Provides
    @Singleton
    fun provideServiceLauncher(
        application: Application,
        appLifecycleTracker: AppLifecycleTracker
    ): ServiceLauncher {
        return ServiceLauncherImpl(application, appLifecycleTracker)
    }
}
