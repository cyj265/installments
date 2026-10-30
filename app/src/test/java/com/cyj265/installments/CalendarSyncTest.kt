package com.cyj265.installments

import com.cyj265.installments.data.CalendarPlan
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarSyncTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun loan(
        id: String,
        periods: List<Period>,
        status: LoanStatus = LoanStatus.ACTIVE,
    ) = Loan(
        id = id,
        institution = "交通银行",
        totalPeriods = periods.size,
        perPeriodAmountCents = 754_886,
        dueDayOfMonth = 9,
        firstDueDate = periods.first().dueDate,
        createdAt = LocalDate.of(2026, 10, 20),
        updatedAt = Instant.parse("2026-10-20T00:00:00Z"),
        status = status,
        periods = periods,
    )

    private fun period(index: Int, due: String, paid: Boolean = false, principal: Long? = null, interest: Long? = null) =
        Period(
            index = index,
            dueDate = LocalDate.parse(due),
            amountCents = 754_886,
            paid = paid,
            principalCents = principal,
            interestCents = interest,
        )

    private val threePeriods = listOf(
        period(1, "2026-10-09", paid = true, principal = 690_171, interest = 64_715),
        period(2, "2026-11-09", principal = 700_559, interest = 54_327),
        period(3, "2026-12-09", principal = 711_102, interest = 43_784),
    )

    // 已还的期不写：App 里点「已还」后，日历里那一期就该消失，靠的就是这里少生成一条
    @Test fun writesOnlyUnpaidPeriods() {
        val entries = CalendarPlan.entries(listOf(loan("a", threePeriods)), shanghai)
        assertEquals(2, entries.size)
        assertEquals(listOf("${CalendarPlan.SCHEME}loan/a/period/2", "${CalendarPlan.SCHEME}loan/a/period/3"), entries.map { it.marker })
    }

    @Test fun reminderLandsAtNineLocalMorning() {
        val entries = CalendarPlan.entries(listOf(loan("a", threePeriods)), shanghai)
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(entries.first().startMillis), shanghai)
        assertEquals(LocalDateTime.parse("2026-11-09T09:00:00"), start)
    }

    @Test fun settledLoanWritesNothing() {
        val entries = CalendarPlan.entries(listOf(loan("a", threePeriods, LoanStatus.SETTLED)), shanghai)
        assertTrue(entries.isEmpty())
    }

    @Test fun titleCarriesInstitutionAndAmountAndBodyCarriesSplit() {
        val first = CalendarPlan.entries(listOf(loan("a", threePeriods)), shanghai).first()
        assertEquals("分期还款 · 交通银行 7,548.86", first.title)
        assertTrue(first.description.contains("第 2/3 期"))
        assertTrue(first.description.contains("本金 7,005.59 + 利息 543.27"))
    }

    // 只有「上次写过、这次没了、而这期还欠着」才算疑似被人删了
    @Test fun vanishedOnlyCountsPeriodsStillOwed() {
        val a2 = "${CalendarPlan.SCHEME}loan/a/period/2"
        val a3 = "${CalendarPlan.SCHEME}loan/a/period/3"
        val a1 = "${CalendarPlan.SCHEME}loan/a/period/1"
        assertEquals(
            listOf(a2),
            CalendarPlan.vanishedMarkers(lastWritten = setOf(a2, a3), existing = setOf(a3), expected = setOf(a2, a3)),
        )
        // 第 1 期已经还了，事件本来就该被删，不是「被人删掉」的信号
        assertEquals(
            emptyList<String>(),
            CalendarPlan.vanishedMarkers(lastWritten = setOf(a1), existing = emptySet(), expected = setOf(a2, a3)),
        )
    }
}
