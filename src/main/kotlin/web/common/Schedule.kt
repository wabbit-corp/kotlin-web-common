package web.common

import kotlinx.serialization.Serializable
import java.util.SplittableRandom
import kotlin.time.Duration

// A schedule can be thought of as a way to represent a finite or infinite
// sequence of time intervals.
@Serializable sealed interface Schedule {
    @Serializable data object Now : Schedule
    @Serializable data object Never : Schedule
    @Serializable data class Recurs(val times: Int, val interval: Duration) : Schedule
    @Serializable data class Fixed(val delays: List<Duration>) : Schedule
    @Serializable data class Exponential(val initialDelay: Duration, val factor: Double) : Schedule

    // Combines two schedules through union, by recurring if either schedule wants to recur,
    // using the minimum of the two delays between recurrences.
    @Serializable data class Union(val a: Schedule, val b: Schedule) : Schedule

    // Combines two schedules through the intersection, by recurring only if both schedules want to recur,
    // using the maximum of the two delays between recurrences.
    @Serializable data class Intersection(val a: Schedule, val b: Schedule) : Schedule

    // Combines two schedules sequentially, by following the first policy until it ends,
    // and then following the second policy.
    @Serializable data class Sequence(val a: Schedule, val b: Schedule) : Schedule

    // A jittered is a combinator that takes one schedule and returns another schedule
    // of the same type except for the delay which is applied randomly.
    @Serializable data class Jittered(val schedule: Schedule, val minScaler: Double, val maxScaler: Double) : Schedule

    @Serializable data class WithCutoff(val schedule: Schedule, val duration: Duration) : Schedule
}
