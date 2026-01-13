// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain.usecase

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class FormatSentencesUseCaseTest {

    private val useCase = FormatSentencesUseCase()

    @Test
    fun ellipsis_does_not_trigger_sentence_break() {
        val input = "Euh... Le chien mange."

        val result = useCase(input, Locale.FRENCH)

        assertEquals("Euh... Le chien mange.", result)
    }

    @Test
    fun multiple_sentences_are_formatted() {
        val input = "The dog eat. The cat sleep."

        val result = useCase(input, Locale.ENGLISH)

        assertEquals("The dog eat.\n\nThe cat sleep.", result)
    }
}
