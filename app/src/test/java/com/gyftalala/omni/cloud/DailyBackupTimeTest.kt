package com.gyftalala.omni.cloud

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DailyBackupTimeTest {
    private fun next(now: String, h: Int = 3, m: Int = 0, zone: String = "Asia/Kolkata") =
        DailyBackupTime.next(Instant.parse(now), h, m, ZoneId.of(zone)).toString()
    @Test fun beforeTimeUsesToday() { assertEquals("2026-09-09T21:30:00Z", next("2026-09-09T20:00:00Z")) }
    @Test fun atTimeUsesNextDay() { assertEquals("2026-09-10T21:30:00Z", next("2026-09-09T21:30:00Z")) }
    @Test fun afterTimeUsesNextDay() { assertEquals("2026-09-10T21:30:00Z", next("2026-09-09T22:00:00Z")) }
    @Test fun midnightAndMinutesWork() { assertEquals("2026-09-10T18:35:00Z", next("2026-09-10T18:34:00Z",0,5)) }
    @Test fun springGapMovesForward() { assertEquals("2026-03-08T07:30:00Z", next("2026-03-08T05:00:00Z",2,30,"America/New_York")) }
    @Test fun fallOverlapDoesNotRepeatToday() { assertEquals("2026-11-02T06:30:00Z", next("2026-11-01T05:45:00Z",1,30,"America/New_York")) }
    @Test fun travelUsesNewZone() { assertEquals("2026-09-10T03:00:00Z", next("2026-09-10T02:00:00Z",zone="UTC")) }
    @Test(expected = IllegalArgumentException::class) fun invalidHourRejected() { next("2026-09-10T00:00:00Z",24) }
}
