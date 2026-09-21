package com.gyftalala.omni.ai

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class TimeParserTest {
    private val now = ZonedDateTime.parse("2026-09-09T18:00:00+05:30[Asia/Kolkata]")
    private fun at(text: String) = TimeParser.parse(text, now)?.at
    @Test fun `relative minutes are precise`() { assertEquals(now.plusMinutes(20).toInstant().toEpochMilli(), at("in 20 minutes")) }
    @Test fun `tomorrow pm preserves local timezone`() { assertEquals(now.plusDays(1).withHour(20).toInstant().toEpochMilli(), at("tomorrow at 8 pm")) }
    @Test fun `past today rejected`() { assertNull(at("today 8 am")) }
    @Test fun `bare time rolls to next occurrence`() { assertEquals(now.plusDays(1).withHour(8).toInstant().toEpochMilli(), at("8 am")) }
    @Test fun `24 hour time`() { assertEquals(now.withHour(21).withMinute(30).toInstant().toEpochMilli(), at("21:30")) }
    @Test fun `invalid clock rejected`() { assertNull(at("tomorrow 25:99")); assertNull(at("13 pm")); assertNull(at("0 am")) }
    @Test fun `vague times require clarification`() { assertNull(at("tomorrow")); assertNull(at("at 8")); assertNull(at("tomorrow evening")) }
    @Test fun `unsupported dates never silently become today`() { assertNull(at("Sep 15 at 8 pm")); assertNull(at("12/09 at 8 pm")); assertNull(at("next week 8 pm")) }
    @Test fun `ISO date supported`() { assertEquals(ZonedDateTime.parse("2026-09-15T20:00:00+05:30[Asia/Kolkata]").toInstant().toEpochMilli(), at("2026-09-15 at 8 pm")) }
    @Test fun `daily repeat explicit`() { assertEquals("daily", TimeParser.parse("every day 8 am", now)?.repeat) }
    @Test fun `weekday repeat explicit`() {
        val parsed = TimeParser.parse("weekdays at 8 am", now)!!
        assertEquals("weekdays", parsed.repeat)
        assertEquals(ZonedDateTime.parse("2026-09-10T08:00:00+05:30[Asia/Kolkata]").toInstant().toEpochMilli(), parsed.at)
    }
    @Test fun `named day repeat explicit`() {
        val parsed = TimeParser.parse("every Monday at 8 am", now)!!
        assertEquals("weekly:MONDAY", parsed.repeat)
        assertEquals(ZonedDateTime.parse("2026-09-14T08:00:00+05:30[Asia/Kolkata]").toInstant().toEpochMilli(), parsed.at)
    }
    @Test fun `unsupported repeat rejected`() { assertNull(at("every month 8 pm")); assertNull(at("weekly 8 pm")) }
    @Test fun `new task not mistaken for time reply`() { assertFalse(TimeParser.isTimeReply("Call doctor tomorrow at 8 pm")); assertTrue(TimeParser.isTimeReply("tomorrow at 8 pm")) }
    @Test fun `weekday schedule is accepted as follow-up`() { assertTrue(TimeParser.isTimeReply("weekdays at 8 am")) }
    @Test fun `zero relative time rejected`() { assertNull(at("in 0 minutes")) }
    @Test fun `conflicting relative and clock times need clarification`() { assertNull(at("in 2 days at 8 pm")); assertNull(at("every day in 20 minutes")) }
    @Test fun `DST nonexistent time rejected`() { assertNull(TimeParser.parse("2026-03-08 at 2:30 am", ZonedDateTime.parse("2026-03-07T12:00:00-05:00[America/New_York]"))) }
}
