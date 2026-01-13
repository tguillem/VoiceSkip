// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain.usecase

import android.icu.text.BreakIterator
import java.util.Locale
import javax.inject.Inject

class FormatSentencesUseCase @Inject constructor() {

    operator fun invoke(text: String, locale: Locale = Locale.getDefault()): String {
        val localeWithSuppression = Locale.Builder()
            .setLocale(locale)
            .setUnicodeLocaleKeyword("ss", "standard")
            .build()

        val iterator = BreakIterator.getSentenceInstance(localeWithSuppression)
        iterator.setText(text)

        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) {
                sentences.add(sentence)
            }
            start = end
            end = iterator.next()
        }

        // Merge sentences where the previous one ends with ellipsis
        val merged = mutableListOf<String>()
        for (sentence in sentences) {
            val last = merged.lastOrNull()
            if (last != null && last.endsWith("...")) {
                merged[merged.lastIndex] = "$last $sentence"
            } else {
                merged.add(sentence)
            }
        }

        return merged.joinToString("\n\n")
    }
}
