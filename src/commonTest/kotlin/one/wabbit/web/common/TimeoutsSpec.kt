package one.wabbit.web.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

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
}
