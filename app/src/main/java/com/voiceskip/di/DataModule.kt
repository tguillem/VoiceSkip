// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.di

import com.voiceskip.data.repository.TranscriptionRepository
import com.voiceskip.data.repository.TranscriptionRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindTranscriptionRepository(
        impl: TranscriptionRepositoryImpl
    ): TranscriptionRepository
}
