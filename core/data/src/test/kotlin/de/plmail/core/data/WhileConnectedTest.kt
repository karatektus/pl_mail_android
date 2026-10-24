package de.plmail.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Live updates follow the stored connection (#43).
 *
 * They are started when the app becomes visible, which on a first launch is the onboarding screen:
 * nothing is stored yet, and the work used to run once, find nothing and end — so a phone paired a
 * moment later got nothing live until it had been in the background and back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhileConnectedTest {

    @Test
    fun `work starts the moment a connection is stored, not only when the app appears`() = runTest {
        val connection = MutableStateFlow<String?>(null)
        val started = mutableListOf<String>()

        val job = launch {
            whileConnected(connection) {
                started += it
                awaitCancellation()
            }
        }
        runCurrent()
        assertEquals(emptyList(), started, "nothing runs on the onboarding screen")

        connection.value = "paired"
        runCurrent()
        assertEquals(listOf("paired"), started)

        job.cancel()
    }

    @Test
    fun `a different connection restarts the work, and a removed one stops it`() = runTest {
        val connection = MutableStateFlow<String?>("first")
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        val job = launch {
            whileConnected(connection) {
                started += it
                try {
                    awaitCancellation()
                } finally {
                    stopped += it
                }
            }
        }
        runCurrent()

        connection.value = "second"
        runCurrent()
        assertEquals(listOf("first", "second"), started)
        assertEquals(listOf("first"), stopped, "the old stream does not outlive its credential")

        connection.value = null
        runCurrent()
        assertEquals(listOf("first", "second"), stopped)

        job.cancel()
    }
}
