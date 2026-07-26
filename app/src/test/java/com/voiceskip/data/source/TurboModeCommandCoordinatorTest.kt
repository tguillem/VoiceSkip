// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TurboModeCommandCoordinatorTest {

    private val threadCounts = TranscriptionThreadCounts(
        standard = 2,
        turbo = 7
    )

    @Test
    fun `start selection follows mode command order`() {
        val coordinator = TurboModeCommandCoordinator()
        val commands = mutableListOf<String>()

        coordinator.enqueueStart(threadCounts) { commands += "start:$it" }
        coordinator.enqueueLoad { commands += "load" }
        coordinator.enqueueStart(threadCounts) { commands += "start:$it" }
        coordinator.onModelLoaded()
        assertThat(coordinator.isLoaded).isTrue()

        coordinator.enqueueUnload { commands += "unload" }
        coordinator.onModelLoaded()
        coordinator.enqueueStart(threadCounts) { commands += "start:$it" }

        assertThat(coordinator.isLoaded).isFalse()
        assertThat(commands).containsExactly(
            "start:2",
            "load",
            "start:7",
            "unload",
            "start:2"
        ).inOrder()
    }

    @Test
    fun `start cannot pass a load command being enqueued`() {
        val coordinator = TurboModeCommandCoordinator()

        assertModeCommandPrecedesStart(
            coordinator = coordinator,
            commandName = "load",
            expectedThreads = threadCounts.turbo,
            enqueueModeCommand = coordinator::enqueueLoad
        )
    }

    @Test
    fun `start cannot pass an unload command being enqueued`() {
        val coordinator = TurboModeCommandCoordinator()
        coordinator.enqueueLoad {}

        assertModeCommandPrecedesStart(
            coordinator = coordinator,
            commandName = "unload",
            expectedThreads = threadCounts.standard,
            enqueueModeCommand = coordinator::enqueueUnload
        )
    }

    @Test
    fun `failed mode commands restore the prior thread selection`() {
        val coordinator = TurboModeCommandCoordinator()
        val failure = IllegalStateException("Submission failed")

        assertThrows(IllegalStateException::class.java) {
            coordinator.enqueueLoad { throw failure }
        }
        assertThat(selectedThreads(coordinator)).isEqualTo(threadCounts.standard)

        coordinator.enqueueLoad {}
        coordinator.onModelLoaded()

        assertThrows(IllegalStateException::class.java) {
            coordinator.enqueueLoad { throw failure }
        }
        assertThat(coordinator.isLoaded).isTrue()
        assertThat(selectedThreads(coordinator)).isEqualTo(threadCounts.turbo)

        assertThrows(IllegalStateException::class.java) {
            coordinator.enqueueUnload { throw failure }
        }
        assertThat(coordinator.isLoaded).isTrue()
        assertThat(selectedThreads(coordinator)).isEqualTo(threadCounts.turbo)
    }

    private fun selectedThreads(coordinator: TurboModeCommandCoordinator): Int {
        var selected = 0
        coordinator.enqueueStart(threadCounts) { selected = it }
        return selected
    }

    private fun assertModeCommandPrecedesStart(
        coordinator: TurboModeCommandCoordinator,
        commandName: String,
        expectedThreads: Int,
        enqueueModeCommand: ((() -> Unit) -> Unit)
    ) {
        val commands = CopyOnWriteArrayList<String>()
        val modeCommandEntered = CountDownLatch(1)
        val releaseModeCommand = CountDownLatch(1)
        val startAttempted = CountDownLatch(1)
        val startEnqueued = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val modeFuture = executor.submit {
                enqueueModeCommand {
                    modeCommandEntered.countDown()
                    check(releaseModeCommand.await(5, TimeUnit.SECONDS))
                    commands += commandName
                }
            }
            assertThat(modeCommandEntered.await(5, TimeUnit.SECONDS)).isTrue()

            val startFuture = executor.submit {
                startAttempted.countDown()
                coordinator.enqueueStart(threadCounts) {
                    commands += "start:$it"
                    startEnqueued.countDown()
                }
            }
            assertThat(startAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(startEnqueued.await(100, TimeUnit.MILLISECONDS)).isFalse()

            releaseModeCommand.countDown()
            modeFuture.get(5, TimeUnit.SECONDS)
            startFuture.get(5, TimeUnit.SECONDS)

            assertThat(commands).containsExactly(
                commandName,
                "start:$expectedThreads"
            ).inOrder()
        } finally {
            releaseModeCommand.countDown()
            executor.shutdownNow()
        }
    }
}
