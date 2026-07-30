// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import android.app.Application
import android.content.pm.PackageManager
import javax.inject.Inject
import javax.inject.Singleton

private const val VULKAN_1_2 = 0x00402000

interface VulkanSupportDataSource {
    fun isVulkan12OrNewer(): Boolean
}

@Singleton
class VulkanSupportDataSourceImpl @Inject constructor(
    application: Application
) : VulkanSupportDataSource {

    private val packageManager = application.packageManager

    override fun isVulkan12OrNewer(): Boolean =
        packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
            VULKAN_1_2
        )
}
