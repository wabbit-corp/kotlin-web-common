package one.wabbit.web.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ScheduleSpec {
    @Test
    fun `parseRetryAfterHeader handles numeric and http date forms`() {
        assertEquals(2.5.seconds, parseRetryAfterHeader("2.5"))

        val parsed =
            parseRetryAfterHeader(
                "Wed, 21 Oct 2037 07:28:00 GMT",
                now = Instant.parse("2037-10-21T07:27:30Z"),
            )

        assertNotNull(parsed)
        assertEquals(30.seconds, parsed)
        assertNull(parseRetryAfterHeader("Wed, 21 Oct 2015 07:28:00 GMT", now = Instant.parse("2037-10-21T07:27:30Z")))
    }
}
