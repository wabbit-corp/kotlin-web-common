// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.web.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimeoutsSpec {
    @Test
    fun `timeouts reject negative durations`() {
        assertFailsWith<IllegalArgumentException> {
            Timeouts(request = (-1).milliseconds)
        }
    }

    @Test
    fun `timeouts reject sub millisecond durations`() {
        assertFailsWith<IllegalArgumentException> {
            Timeouts(connect = 500.microseconds)
        }
    }

    @Test
    fun `timeouts reject non whole millisecond durations`() {
        assertFailsWith<IllegalArgumentException> {
            Timeouts(request = 1500.microseconds)
        }
    }

    @Test
    fun `timeouts reject zero durations`() {
        assertFailsWith<IllegalArgumentException> {
            Timeouts(socket = 0.milliseconds)
        }
    }

    @Test
    fun `timeouts allow null and positive whole millisecond values`() {
        val timeouts =
            Timeouts(
                request = null,
                connect = 1.milliseconds,
                socket = 250.milliseconds,
            )

        assertEquals(null, timeouts.request)
        assertEquals(1.milliseconds, timeouts.connect)
        assertEquals(250.milliseconds, timeouts.socket)
    }

    @Test
    fun `forStreaming disables request timeout and raises socket timeout floor`() {
        val derived =
            Timeouts(
                request = 15.seconds,
                connect = 7.seconds,
                socket = 20.seconds,
            ).forStreaming()

        assertEquals(null, derived.request)
        assertEquals(7.seconds, derived.connect)
        assertEquals(DefaultStreamingSocketTimeout, derived.socket)
    }

    @Test
    fun `forStreaming preserves a larger existing socket timeout`() {
        val derived =
            Timeouts(
                request = 15.seconds,
                connect = 7.seconds,
                socket = 90.seconds,
            ).forStreaming()

        assertEquals(null, derived.request)
        assertEquals(7.seconds, derived.connect)
        assertEquals(90.seconds, derived.socket)
    }

    @Test
    fun `forStreaming applies configured socket timeout floor when socket timeout is unset`() {
        val derived =
            Timeouts(
                request = 15.seconds,
                connect = 7.seconds,
                socket = null,
            ).forStreaming(45.seconds)

        assertEquals(null, derived.request)
        assertEquals(7.seconds, derived.connect)
        assertEquals(45.seconds, derived.socket)
    }

    @Test
    fun `forStreaming rejects invalid minimum socket timeout`() {
        assertFailsWith<IllegalArgumentException> {
            Timeouts().forStreaming(0.milliseconds)
        }
    }
}
