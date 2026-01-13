// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import kotlinx.serialization.Serializable

@Serializable
data class SavedTranscription(
    val text: String,
    val timestamp: Long,
    val durationMs: Long,
    val audioLengthMs: Int,
    val detectedLanguage: String,
    val segments: List<SavedSegment>
)

@Serializable
data class SavedSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val language: String?
)
