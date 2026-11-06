package com.snapbrain.domain

/** The point economy, skip budget and free-time windows, in one place so they stay consistent. */
object Rules {
    /** Points needed for one ticket. */
    const val POINTS_PER_TICKET = 5

    /** Free scrolling time bought by one ticket. */
    const val TICKET_MINUTES = 10

    /** Free scrolling time after finishing one card (or skipping) at the intercept screen. */
    const val PASS_MINUTES = 5

    /** "Bận việc thật" skips per day in soft mode. Strict mode has none. */
    const val SOFT_SKIPS_PER_DAY = 3

    /** Correct answers in a row needed to repair yesterday's missed streak day. */
    const val SAVER_STREAK = 3

    /** A card earns a point only the first time it's answered correctly on a given day (no farming). */
    fun pointsFor(correct: Boolean, lastPointDay: Long?, today: Long): Int =
        if (correct && lastPointDay != today) 1 else 0

    fun skipsLeft(strict: Boolean, usedDay: Long, used: Int, today: Long): Int {
        if (strict) return 0
        val usedToday = if (usedDay == today) used else 0
        return (SOFT_SKIPS_PER_DAY - usedToday).coerceAtLeast(0)
    }

    /** New free-until after buying [tickets]; buying while free time is left extends it. */
    fun extendFreeTime(freeUntil: Long, now: Long, tickets: Int): Long =
        maxOf(freeUntil, now) + tickets * TICKET_MINUTES * 60_000L

    fun pass(freeUntil: Long, now: Long): Long = maxOf(freeUntil, now + PASS_MINUTES * 60_000L)
}

object Streak {
    /**
     * [current]: consecutive studied days ending today, or ending yesterday if today isn't studied yet.
     * [repairableDay]: yesterday, if it was missed but the day before was studied (whether or not today is
     * studied yet) — the streak can be
     * saved by [Rules.SAVER_STREAK] correct answers in a row. [brokenLength] is the streak that would return.
     */
    data class Status(
        val current: Int,
        val studiedToday: Boolean,
        val repairableDay: Long?,
        val brokenLength: Int,
    )

    fun compute(days: Set<Long>, today: Long): Status {
        val studiedToday = today in days
        val current = when {
            studiedToday -> runLength(days, today)
            (today - 1) in days -> runLength(days, today - 1)
            else -> 0
        }
        // Studying today doesn't close the window: the saver's own answers count for today.
        val repairable = (today - 1) !in days && (today - 2) in days
        return Status(
            current = current,
            studiedToday = studiedToday,
            repairableDay = if (repairable) today - 1 else null,
            brokenLength = if (repairable) runLength(days, today - 2) else 0,
        )
    }

    private fun runLength(days: Set<Long>, end: Long): Int {
        var n = 0
        while ((end - n) in days) n++
        return n
    }
}

object InterceptPolicy {
    sealed interface Decision {
        data object Allow : Decision

        /** Allowed while free time lasts; re-check at [until]. */
        data class AllowUntil(val until: Long) : Decision
        data object Intercept : Decision
    }

    fun decide(pkg: String, now: Long, blocked: Set<String>, freeUntil: Long): Decision = when {
        pkg !in blocked -> Decision.Allow
        now < freeUntil -> Decision.AllowUntil(freeUntil)
        else -> Decision.Intercept
    }

    /** Packages whose windows never count as "leaving" the current app (shade, keyboard, ourselves). */
    fun isTransient(pkg: String, ownPkg: String, keyboards: Set<String>): Boolean =
        pkg == ownPkg || pkg == "com.android.systemui" || pkg in keyboards
}
