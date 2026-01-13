// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartupConfig @Inject constructor() {
    @Volatile
    var skipModelLoad: Boolean = false
}
