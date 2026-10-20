package com.cyj265.installments

import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.JsonLoanStore
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import com.cyj265.installments.data.Period
import com.cyj265.installments.domain.DraftMatch
import com.cyj265.installments.domain.FindingLevel
import com.cyj265.installments.domain.LoanAssembler
import com.cyj265.installments.domain.LoanIdentity
import com.cyj265.installments.domain.LoanValidator
import com.cyj265.installments.domain.Portfolio
import com.cyj265.installments.domain.hasError
import com.cyj265.installments.domain.matchLoan
import com.cyj265.installments.domain.mergeLoan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class MoneyTest {
    @Test fun parsesCurrencyStrings() {
        assertEquals(123450L, Money.parseCents("¥1,234.50"))
        assertEquals(68000L, Money.parseCents("680"))
        assertEquals(68000L, Money.parseCents("￥680.00元"))
        assertEquals(1235L, Money.parseCents("12.345"))
        assertNull(Money.parseCents("abc"))
        assertNull(Money.parseCents(""))
        assertNull(Money.parseCents(null))
        assertNull(Money.parseCents("-100"))
    }

    @Test fun formatsWithGrouping() {
        assertEquals("5,440.00", Money.format(544000L))
        assertEquals("0.00", Money.format(0L))
        assertEquals("¥18,640.00", Money.formatWithSymbol(1864000L))
    }
}

class LoanAssemblerTest {

    private val huabei = InstallmentDraft(
        institution = "支付宝花呗",
        loanNo = "****3271",
        totalPeriods = 12,
        currentPeriod = 5,
        perPeriodAmount = "680.00",
        principalOutstanding = "4,760.00",
        dueDayOfMonth = 28,
        nextDueDate = "2026-10-28",
    )

    @Test fun expandsPeriodsFromCurrentPosition() {
        val loan = LoanAssembler.fromDraft(huabei, now = LocalDate.of(2026, 10, 20))
        assertEquals(12, loan.periods.size)
        assertEquals(LocalDate.of(2026, 6, 28), loan.firstDueDate)
        assertEquals(LocalDate.of(2027, 5, 28), loan.periods.last().dueDate)
        assertEquals(4, loan.paidCount)
        assertEquals(5, loan.nextUnpaid?.index)
        assertEquals(544000L, loan.remainingPayableCents)
    }

    // 手填「每月几号」时，本期要锚在还没到的那一天，否则还款日一过，第一格就是逾期
    @Test fun dueDayAloneAnchorsToNextOccurrence() {
        val loan = LoanAssembler.fromDraft(
            huabei.copy(nextDueDate = null, currentPeriod = 1, totalPeriods = 6),
            now = LocalDate.of(2026, 10, 20),
        )
        assertEquals(LocalDate.of(2026, 10, 28), loan.firstDueDate)
        assertEquals(LocalDate.of(2027, 3, 28), loan.periods.last().dueDate)
    }

    @Test fun dueDayAlreadyPassedRollsToNextMonth() {
        val today = LocalDate.of(2026, 10, 20)
        val loan = LoanAssembler.fromDraft(
            huabei.copy(nextDueDate = null, dueDayOfMonth = 10, currentPeriod = 1, totalPeriods = 3),
            now = today,
        )
        assertEquals(LocalDate.of(2026, 11, 10), loan.firstDueDate)
        assertFalse(loan.isOverdue(today))
    }

    // 锚点定的是「本期」，第 1 期要按 currentPeriod 往前推，否则已还的期数会全跑到未来
    @Test fun dueDayAloneStillBackdatesPaidPeriods() {
        val loan = LoanAssembler.fromDraft(
            huabei.copy(nextDueDate = null, currentPeriod = 5),
            now = LocalDate.of(2026, 10, 20),
        )
        assertEquals(LocalDate.of(2026, 6, 28), loan.firstDueDate)
        assertEquals(4, loan.paidCount)
    }

    @Test fun explicitPaidPeriodsOverridePrefixRule() {
        val loan = LoanAssembler.fromDraft(huabei.copy(paidPeriods = listOf(1, 2, 4)))
        assertEquals(listOf(1, 2, 4), loan.periods.filter { it.paid }.map { it.index })
    }

    // 模型对"没读到"的列表通常回 [] 而不是 null，照单收下会把第 5 期的记录抹成一期未还
    @Test fun emptyPaidPeriodsListFallsBackToCurrentPeriod() {
        val loan = LoanAssembler.fromDraft(huabei.copy(paidPeriods = emptyList()))
        assertEquals(4, loan.paidCount)
    }

    @Test fun marksSettledWhenAllPaid() {
        val loan = LoanAssembler.fromDraft(huabei.copy(currentPeriod = 13, totalPeriods = 12, paidPeriods = (1..12).toList()))
        assertEquals(LoanStatus.SETTLED, loan.status)
        assertEquals(0L, loan.remainingPayableCents)
    }
}

class LoanValidatorTest {

    private fun draft() = InstallmentDraft(
        institution = "支付宝花呗",
        loanNo = "****3271",
        totalPeriods = 12,
        currentPeriod = 5,
        perPeriodAmount = "680.00",
        principalOutstanding = "4,760.00",
        dueDayOfMonth = 28,
        nextDueDate = "2026-10-28",
    )

    @Test fun flagsPrincipalOnePeriodShort() {
        val loan = LoanAssembler.fromDraft(draft())
        val warn = LoanValidator.validateLoan(loan).firstOrNull { it.level == FindingLevel.WARN && it.field == "principalOutstanding" }
        assertNotNull(warn)
        assertTrue(warn!!.message.contains("少正好一期"))
    }

    @Test fun consistentPrincipalProducesNoPrincipalFinding() {
        val loan = LoanAssembler.fromDraft(draft().copy(principalOutstanding = "5,440.00"))
        assertFalse(LoanValidator.validateLoan(loan).any { it.field == "principalOutstanding" })
    }

    @Test fun rejectsPrincipalLargerThanPayable() {
        val loan = LoanAssembler.fromDraft(draft().copy(principalOutstanding = "9,000.00"))
        val finding = LoanValidator.validateLoan(loan).first { it.field == "principalOutstanding" }
        assertEquals(FindingLevel.ERROR, finding.level)
    }

    // 真机案例：账单分期 3 期、序号 0004。「当前期 3」和「下期 2026-12-09」互相矛盾，
    // 计划表从下期往回倒推，第 2 期就落到了还没来的 11-09，却被前缀规则标成已还。
    private val oct20 = LocalDate.of(2026, 10, 20)

    private fun tailDraft() = InstallmentDraft(
        institution = "某银行信用卡",
        loanNo = "0004",
        totalPeriods = 3,
        currentPeriod = 3,
        perPeriodAmount = "5333.34",
        principalOutstanding = "5333.33",
        dueDayOfMonth = 9,
        nextDueDate = "2026-12-09",
    )

    @Test fun flagsPaidPeriodDatedInTheFuture() {
        val loan = LoanAssembler.fromDraft(tailDraft(), now = oct20)
        val finding = LoanValidator.validateLoan(loan, today = oct20).first { it.field == "nextDueDate" }
        assertEquals(FindingLevel.WARN, finding.level)
        assertTrue(finding.message.contains("还没到"))
    }

    @Test fun clearingNextDueDateRealignsTheSchedule() {
        val loan = LoanAssembler.fromDraft(tailDraft().copy(nextDueDate = null), now = oct20)
        assertEquals(
            listOf("2026-09-09", "2026-10-09", "2026-11-09"),
            loan.periods.map { it.dueDate.toString() },
        )
        assertFalse(LoanValidator.validateLoan(loan, today = oct20).any { it.field == "nextDueDate" })
    }

    // 16000÷3 每期 5333.34、尾期本金 5333.33：只剩一期时本金≈单期本来就成立，不该弹黄条
    @Test fun lastPeriodPrincipalIsNotFlagged() {
        val loan = LoanAssembler.fromDraft(tailDraft(), now = oct20)
        assertFalse(LoanValidator.validateLoan(loan, today = oct20).any { it.field == "principalOutstanding" })
    }

    @Test fun draftMissingTotalPeriodsIsError() {
        val findings = LoanValidator.validateDraft(draft().copy(totalPeriods = null))
        assertTrue(findings.hasError())
        assertEquals("totalPeriods", findings.first { it.level == FindingLevel.ERROR }.field)
    }

    @Test fun draftRejectsUnreadableAmount() {
        val findings = LoanValidator.validateDraft(draft().copy(perPeriodAmount = "待定"))
        assertEquals("perPeriodAmount", findings.first { it.level == FindingLevel.ERROR }.field)
    }

    @Test fun draftRejectsCurrentPeriodBeyondTotal() {
        val findings = LoanValidator.validateDraft(draft().copy(currentPeriod = 15))
        assertEquals("currentPeriod", findings.first { it.level == FindingLevel.ERROR }.field)
    }

    @Test fun draftRejectsDueDayInconsistentWithDate() {
        val findings = LoanValidator.validateDraft(draft().copy(dueDayOfMonth = 15))
        assertEquals(FindingLevel.WARN, findings.first { it.field == "dueDayOfMonth" }.level)
    }

    // 确认页拿 field 当输入框的键名来标红，这两个键名一旦改了，对应的框就永远不会亮
    @Test fun draftFlagsBlankInstitution() {
        val findings = LoanValidator.validateDraft(draft().copy(institution = "  "))
        assertEquals("institution", findings.first { it.level == FindingLevel.ERROR }.field)
    }

    @Test fun draftFlagsUnreadableDueDate() {
        val findings = LoanValidator.validateDraft(draft().copy(nextDueDate = "下个月"))
        assertEquals("nextDueDate", findings.first { it.level == FindingLevel.ERROR }.field)
    }

    // 交行的分期详情页就没有还款日那一栏，能填的只有「每月几号」，这条路必须走得通
    @Test fun draftAcceptsDueDayAloneWithoutFullDate() {
        assertFalse(LoanValidator.validateDraft(draft().copy(nextDueDate = null)).hasError())
    }

    @Test fun draftFlagsWhenNeitherDateNorDayIsKnown() {
        val findings = LoanValidator.validateDraft(draft().copy(nextDueDate = null, dueDayOfMonth = null))
        assertEquals("nextDueDate", findings.first { it.level == FindingLevel.ERROR }.field)
    }

    @Test fun detectsHoleInPaidPeriods() {
        var loan = LoanAssembler.fromDraft(draft())
        loan = LoanAssembler.togglePaid(loan, 7, true, LocalDate.of(2026, 10, 20))
        val finding = LoanValidator.validateLoan(loan).firstOrNull { it.message.contains("可能漏记") }
        assertNotNull(finding)
        assertTrue(finding!!.message.contains("第 5、6 期"))
    }

    @Test fun flagsAllPaidButStillActive() {
        var loan = LoanAssembler.fromDraft(draft())
        (5..12).forEach { loan = LoanAssembler.togglePaid(loan, it, true, LocalDate.of(2026, 10, 20)) }
        assertEquals(LoanStatus.SETTLED, loan.status)
        assertEquals(FindingLevel.WARN, LoanValidator.validateLoan(loan.copy(status = LoanStatus.ACTIVE)).first { it.message.contains("已结清") }.level)
    }
}

class LoanIdentityTest {

    private val huabei = LoanAssembler.fromDraft(
        InstallmentDraft(
            institution = "花呗",
            loanNo = "****3271",
            totalPeriods = 12,
            currentPeriod = 5,
            perPeriodAmount = "680.00",
            dueDayOfMonth = 28,
            nextDueDate = "2026-10-28",
        )
    )

    @Test fun normalizesInstitutionAliases() {
        assertEquals("支付宝花呗", LoanIdentity.normalizeInstitution(" 蚂蚁花呗 "))
        assertEquals("京东白条", LoanIdentity.normalizeInstitution("白条"))
        assertEquals("微众银行微粒贷", LoanIdentity.normalizeInstitution("微粒贷"))
    }

    // 短别名先命中就会把不同平台并成一家，而合并是覆盖式的，错了不报错
    @Test fun keepsAdjacentPlatformsApart() {
        assertEquals("京东金条", LoanIdentity.normalizeInstitution("京东金条"))
        assertEquals("美团月付", LoanIdentity.normalizeInstitution("美团月付"))
        assertEquals("抖音月付", LoanIdentity.normalizeInstitution("抖音月付"))
    }

    // 银行全称、带「信用卡中心」后缀、带尾号，都得落到同一个机构名，否则下个月一张新截图就变成一笔新债
    @Test fun normalizesBankNameVariants() {
        assertEquals("浦发银行", LoanIdentity.normalizeInstitution("浦发银行信用卡中心"))
        assertEquals("浦发银行", LoanIdentity.normalizeInstitution("上海浦东发展银行股份有限公司"))
        assertEquals("交通银行", LoanIdentity.normalizeInstitution("交通银行信用卡 尾号1430"))
        assertEquals("甘肃农商银行", LoanIdentity.normalizeInstitution("甘肃农村商业银行股份有限公司肃州广场支行"))
    }

    @Test fun maskedLoanNoStillYieldsStrongKey() {
        val key = LoanIdentity.strongKey("支付宝花呗", "****3271")
        assertEquals(key, LoanIdentity.strongKey("支付宝花呗", "借款单号 202603271"))
        assertNull(LoanIdentity.strongKey("支付宝花呗", "**12"))
        assertNull(LoanIdentity.strongKey("支付宝花呗", null))
    }

    @Test fun strongMatchWinsOverWeak() {
        val match = matchLoan(listOf(huabei), "支付宝花呗", "1234563271", 68000L, 28, 12)
        assertTrue(match is DraftMatch.Confirmed)
        assertEquals(huabei.id, (match as DraftMatch.Confirmed).loan.id)
    }

    @Test fun weakOnlyMatchIsSuspect() {
        val match = matchLoan(listOf(huabei), "支付宝花呗", "****9999", 68000L, 28, 12)
        assertTrue(match is DraftMatch.Suspect)
    }

    @Test fun differentAmountIsNoMatch() {
        assertTrue(matchLoan(listOf(huabei), "支付宝花呗", null, 70000L, 28, 12) is DraftMatch.Unknown)
    }

    @Test fun mergeKeepsIdentityAndUnionsPaid() {
        val older = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "支付宝花呗", loanNo = "****3271", totalPeriods = 12, currentPeriod = 3,
                perPeriodAmount = "680.00", dueDayOfMonth = 28, nextDueDate = "2026-08-28",
            ), id = "existing"
        )
        val newer = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "支付宝花呗", loanNo = "****3271", totalPeriods = 12, currentPeriod = 5,
                perPeriodAmount = "680.00", dueDayOfMonth = 28, nextDueDate = "2026-10-28",
            ), id = "fresh"
        )
        val merged = mergeLoan(older, newer)
        assertEquals("existing", merged.id)
        assertEquals(4, merged.paidCount)
        assertEquals(5, merged.nextUnpaid?.index)
        assertNotNull(merged.loanNo)
    }
}

class PortfolioTest {

    private val today = LocalDate.of(2026, 10, 20)

    private fun loan(institution: String, no: String, current: Int, nextDue: String, amount: String) =
        LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = institution, loanNo = no, totalPeriods = 12, currentPeriod = current,
                perPeriodAmount = amount, dueDayOfMonth = 28, nextDueDate = nextDue,
            )
        )

    private val loans = listOf(
        loan("微众银行微粒贷", "****7781", 5, "2026-10-17", "1,240.00"),
        loan("支付宝花呗", "****3271", 5, "2026-10-28", "680.00"),
        loan("招商银行", "****1145", 3, "2026-11-12", "1,000.00"),
    )

    @Test fun countsOverdueSeparately() {
        assertTrue(loans[0].isOverdue(today))
        assertEquals(3, loans[0].overdueDays(today))
        val summary = Portfolio.summarize(loans, today)
        assertEquals(1, summary.overdueCount)
        assertEquals(3, summary.activeCount)
    }

    @Test fun nextDueSkipsOverdue() {
        val summary = Portfolio.summarize(loans, today)
        assertEquals("支付宝花呗", summary.nextDue?.institution)
        assertEquals(8L, summary.daysToNextDue)
    }

    @Test fun monthTotalsCoverWholeCalendarMonth() {
        val summary = Portfolio.summarize(loans, today)
        // 10 月内待还：微粒贷 10-17 的 1,240（已逾期）+ 花呗 10-28 的 680
        assertEquals(2, summary.monthPayableCount)
        assertEquals(192000L, summary.monthPayableCents)
    }

    @Test fun remainingPayableSumsUnpaidOnly() {
        val summary = Portfolio.summarize(loans, today)
        assertEquals(loans.sumOf { it.remainingPayableCents }, summary.remainingPayableCents)
        assertEquals(36, summary.totalPeriods)
        assertEquals(10, summary.paidPeriods)
        assertTrue(summary.hasUnknownPrincipal)
    }

    @Test fun settleEarlyClearsRemaining() {
        val settled = LoanAssembler.settleEarly(Portfolio.activeLoans(loans).first(), today)
        assertEquals(0L, settled.remainingPayableCents)
        assertEquals(LoanStatus.SETTLED, settled.status)
    }

    @Test fun duesByDayGroupsForCalendar() {
        val map = Portfolio.dues(loans, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        assertTrue(map.keys.all { it.monthValue == 10 })
        assertNotNull(map[LocalDate.of(2026, 10, 28)])
    }
}

class JsonLoanStoreTest {

    @Test fun roundTripsLoanThroughFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fq-store-${System.nanoTime()}")
        val store = JsonLoanStore(File(dir, "loans.json"))
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "花呗", loanNo = "****3271", totalPeriods = 3, currentPeriod = 2,
                perPeriodAmount = "680.00", principalOutstanding = "1,360.00",
                dueDayOfMonth = 28, nextDueDate = "2026-10-28",
            )
        )
        runBlocking {
            store.save(listOf(loan))
            store.put(loan.copy(id = "second", institution = "京东白条"))
            val loaded = JsonLoanStore(File(dir, "loans.json")).load()
            assertEquals(2, loaded.size)
            val first = loaded.first { it.id == loan.id }
            assertEquals(LocalDate.of(2026, 9, 28), first.firstDueDate)
            assertEquals(68000L, first.perPeriodAmountCents)
            assertEquals(136000L, first.principalOutstandingCents)
            assertEquals(listOf(1, 2, 3), first.periods.map { it.index })
            assertEquals(LocalDate.of(2026, 10, 28), first.periods[1].dueDate)
            store.remove("second")
        }
        assertEquals(1, runBlocking { JsonLoanStore(File(dir, "loans.json")).load() }.size)
        dir.deleteRecursively()
    }

    @Test fun corruptedFileIsMovedAsideNotSilentlyIgnored() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fq-corrupt-${System.nanoTime()}")
        dir.mkdirs()
        val file = File(dir, "loans.json")
        file.writeText("{ this is not json")
        val loaded = runBlocking { JsonLoanStore(file).load() }
        assertTrue(loaded.isEmpty())
        assertTrue(dir.listFiles()?.any { it.name.startsWith("loans.json.corrupt-") } == true)
        assertFalse(file.exists())
        dir.deleteRecursively()
    }

    @Test fun periodKeepsPaidOnDate() {
        val period = Period(1, LocalDate.of(2026, 9, 28), 68000L, paid = true, paidOn = LocalDate.of(2026, 9, 27))
        val loan = Loan(
            id = "x", institution = "京东白条", totalPeriods = 1, perPeriodAmountCents = 68000L,
            dueDayOfMonth = 28, firstDueDate = period.dueDate, createdAt = LocalDate.of(2026, 9, 1),
            updatedAt = java.time.Instant.ofEpochSecond(0), periods = listOf(period),
        )
        val dir = File(System.getProperty("java.io.tmpdir"), "fq-paid-${System.nanoTime()}")
        val store = JsonLoanStore(File(dir, "loans.json"))
        runBlocking { store.save(listOf(loan)) }
        val reread = runBlocking { JsonLoanStore(File(dir, "loans.json")).load() }.first()
        assertEquals(LocalDate.of(2026, 9, 27), reread.periods.first().paidOn)
        assertEquals(1, reread.paidCount)
        assertEquals(LoanStatus.ACTIVE, reread.status)
        dir.deleteRecursively()
    }
}
