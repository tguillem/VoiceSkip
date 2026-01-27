// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.di

import com.voiceskip.data.repository.AudioPlaybackRepository
import com.voiceskip.data.repository.AudioPlaybackRepositoryImpl
import com.voiceskip.data.repository.DefaultMediaPlayerFactory
import com.voiceskip.data.repository.MediaPlayerFactory
import com.voiceskip.data.repository.TranscriptionRepository
import com.voiceskip.data.repository.TranscriptionRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindTranscriptionRepository(
        impl: TranscriptionRepositoryImpl
    ): TranscriptionRepository

    @Binds
    @Singleton
    abstract fun bindAudioPlaybackRepository(
        impl: AudioPlaybackRepositoryImpl
    ): AudioPlaybackRepository

    @Binds
    @Singleton
    abstract fun bindMediaPlayerFactory(
        impl: DefaultMediaPlayerFactory
    ): MediaPlayerFactory
}
