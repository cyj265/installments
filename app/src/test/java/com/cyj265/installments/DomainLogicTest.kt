package com.cyj265.installments

import com.cyj265.installments.data.DraftPeriod
import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.JsonLoanStore
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import com.cyj265.installments.data.Period
import com.cyj265.installments.data.Platforms
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

    // 明细页改金额只该动钱，不该动时间轴：期号、应还日、已还标记必须原样留着
    @Test fun editsRepriceEveryPeriodWithoutTouchingTheSchedule() {
        val loan = LoanAssembler.fromDraft(huabei, now = LocalDate.of(2026, 10, 20))
        val edited = LoanAssembler.applyEdits(loan, "支付宝花呗", "****3271", 70_000L, 476_000L, null)
        assertEquals(loan.periods.map { it.index to it.dueDate }, edited.periods.map { it.index to it.dueDate })
        assertEquals(4, edited.paidCount)
        assertEquals(listOf(70_000L), edited.periods.map { it.amountCents }.distinct())
        assertEquals(560_000L, edited.remainingPayableCents)
    }

    // 改名最容易犯的错是凭空多出一条：id 或 createdAt 一变，台账里就是两笔债
    @Test fun editsKeepIdentitySoRenamingDoesNotSpawnASecondRecord() {
        val loan = LoanAssembler.fromDraft(huabei)
        val edited = LoanAssembler.applyEdits(loan, "  招行  ", "  0004  ", 68_000L, null, null)
        assertEquals(loan.id, edited.id)
        assertEquals(loan.createdAt, edited.createdAt)
        assertEquals("招商银行", edited.institution)
        assertEquals("0004", edited.loanNo)
    }

    @Test fun blankLoanNoAndPrincipalEditsClearTheFieldsInsteadOfStoringSpaces() {
        val loan = LoanAssembler.fromDraft(huabei)
        val edited = LoanAssembler.applyEdits(loan, "支付宝花呗", "   ", 68_000L, null, null)
        assertNull(edited.loanNo)
        assertNull(edited.principalOutstandingCents)
    }

    // 真机那笔 0004：每期漏了 80 元手续费，未还本金抄成了「剩余未摊销」。
    // 这两个数差得太小，校验规则抓不到，只能人在明细页改——改对之后不该再报口径问题。
    @Test fun correctingAmountsClearsThePrincipalFindings() {
        val card = InstallmentDraft(
            institution = "信用卡",
            loanNo = "0004",
            totalPeriods = 3,
            currentPeriod = 2,
            perPeriodAmount = "5,333.34",
            principalOutstanding = "5,333.33",
            dueDayOfMonth = 9,
            nextDueDate = "2026-11-09",
        )
        val loan = LoanAssembler.fromDraft(card, now = LocalDate.of(2026, 10, 20))
        val fixed = LoanAssembler.applyEdits(loan, "某银行", "0004", 541_334L, 1_066_666L, null)
        assertEquals(1, fixed.paidCount)
        assertEquals(1_082_668L, fixed.remainingPayableCents)
        val findings = LoanValidator.validateLoan(fixed, today = LocalDate.of(2026, 10, 20))
        assertFalse(findings.any { it.field == "principalOutstanding" })
    }

    // 明细页的总计卡：一期没还时「未还本金」就是整笔本金，可以直接当总额；
    // 还过款之后它只剩剩余部分，再拿它冒充总额会把总息费算少，所以必须返回 null 让界面显示「—」。
    @Test fun totalPrincipalOnlyBorrowsFromOutstandingBeforeAnyPayment() {
        val untouched = LoanAssembler.fromDraft(huabei.copy(currentPeriod = 1, paidPeriods = null), now = LocalDate.of(2026, 10, 20))
        assertEquals(0, untouched.paidCount)
        assertEquals(476_000L, untouched.principalTotalOrNull)
        assertEquals(816_000L, untouched.planTotalCents)

        val partial = LoanAssembler.fromDraft(huabei, now = LocalDate.of(2026, 10, 20))
        assertNull(partial.principalTotalOrNull)
        // 已还 4 期、剩 8 期，未还本金 4,760 ÷ 8 × 12 = 7,140
        assertEquals(714_000L, partial.principalTotalDerivedCents)
    }

    @Test fun applyingEditsStoresThePrincipalTotal() {
        val loan = LoanAssembler.fromDraft(huabei, now = LocalDate.of(2026, 10, 20))
        val edited = LoanAssembler.applyEdits(loan, "支付宝花呗", "****3271", 70_000L, 476_000L, 700_000L)
        assertEquals(700_000L, edited.principalTotalCents)
        assertEquals(840_000L, edited.planTotalCents)
    }

    // 真机 0004 分期历史：3 期、商品金额 16,000、利率 0.00% 但每期另有 80 元费用。
    // 月还款额 5,333.34 只是本金部分，把费用加回去才是每期实还 5,413.34——
    // 自证：已摊销金额 10,826.67 ÷ 2 期 = 5,413.335，剩余未摊销 5,333.33 恰是不含费用的本金。
    @Test fun feePerPeriodIsPartOfTheMonthlyAmount() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "某银行信用卡",
                loanNo = "0004",
                totalPeriods = 3,
                currentPeriod = 3,
                perPeriodAmount = "5413.34",
                principalOutstanding = "5333.33",
                principalTotal = "16000.00",
                dueDayOfMonth = 9,
                nextDueDate = "2026-11-09",
            ),
            now = LocalDate.of(2026, 10, 20),
        )
        assertEquals(2, loan.paidCount)
        assertEquals(541_334L, loan.perPeriodAmountCents)
        assertEquals(1_624_002L, loan.planTotalCents)
        assertEquals(1_600_000L, loan.principalTotalOrNull)
        assertEquals(24_002L, loan.planTotalCents - loan.principalTotalOrNull!!)
    }

    // 真机 0004/0003 分期历史：页面上根本没有还款日，识别时退化成「每月 1 日」，首期就错了半个月。
    // 真实账单日是 15 日：只改日号、不挪月份，0004 首期该是 2026-09-15，0003 首期该是 2026-06-15。
    @Test fun changingDueDayRelaysThePlanWithoutTouchingPaidFlags() {
        val three = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "交通银行",
                loanNo = "0004",
                totalPeriods = 3,
                remainingPeriods = 1,
                perPeriodAmount = "5413.34",
                dueDayOfMonth = 1,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(LocalDate.of(2026, 9, 1), three.firstDueDate)

        val fixedThree = LoanAssembler.applyEdits(three, "交通银行", "0004", 541_334L, null, null, dueDayOfMonth = 15)
        assertEquals(15, fixedThree.dueDayOfMonth)
        assertEquals(LocalDate.of(2026, 9, 15), fixedThree.firstDueDate)
        assertEquals(LocalDate.of(2026, 11, 15), fixedThree.nextUnpaid?.dueDate)
        assertEquals(2, fixedThree.paidCount)
        assertEquals(listOf(15, 15, 15), fixedThree.periods.map { it.dueDate.dayOfMonth })
    }

    @Test fun changingDueDayKeepsTheMonthsOfASixPeriodPlan() {
        val six = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "交通银行",
                loanNo = "0003",
                totalPeriods = 6,
                remainingPeriods = 1,
                perPeriodAmount = "10999.99",
                dueDayOfMonth = 1,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(LocalDate.of(2026, 6, 1), six.firstDueDate)

        val fixedSix = LoanAssembler.applyEdits(six, "交通银行", "0003", 1_099_999L, null, null, dueDayOfMonth = 15)
        assertEquals(LocalDate.of(2026, 6, 15), fixedSix.firstDueDate)
        assertEquals(LocalDate.of(2026, 11, 15), fixedSix.periods.last().dueDate)
        assertEquals(5, fixedSix.paidCount)
    }

    // 2 月没有 31 日：改到 31 要落在月末，而不是把那一期跳到 3 月
    @Test fun dueDayBeyondMonthLengthFallsOnTheLastDay() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(institution = "某银行", totalPeriods = 3, currentPeriod = 1, perPeriodAmount = "100.00", dueDayOfMonth = 1),
            now = LocalDate.of(2026, 1, 1),
        )
        val fixed = LoanAssembler.applyEdits(loan, "某银行", null, 10_000L, null, null, dueDayOfMonth = 31)
        assertEquals(listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)), fixed.periods.map { it.dueDate })
    }

    // 已经入库的 0004（记账时还没有账单日和交易日期）：在明细页把三个日期补齐，首期要跟着重算
    @Test fun editingDatesInDetailRecomputesTheWholePlan() {
        val wrong = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "交通银行",
                loanNo = "0004",
                totalPeriods = 3,
                remainingPeriods = 1,
                perPeriodAmount = "5413.34",
                dueDayOfMonth = 1,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(LocalDate.of(2026, 9, 1), wrong.firstDueDate)

        val fixed = LoanAssembler.applyEdits(
            wrong, "交通银行", "0004", 541_334L, null, null,
            dueDayOfMonth = 15,
            statementDay = 15,
            tradeDate = LocalDate.of(2026, 8, 25),
        )
        assertEquals(LocalDate.of(2026, 9, 15), fixed.firstDueDate)
        assertEquals(LocalDate.of(2026, 11, 15), fixed.nextUnpaid?.dueDate)
        assertEquals(2, fixed.paidCount)
    }

    // 真机 0004：交易 08-25、账单日 15、还款日 15 → 首期 09-15，和「已摊销 2 期」自洽
    // （09-15、10-15 已还，下期 11-15）。这一条不依赖今天是几号。
    @Test fun firstDueComesFromTradeDateStatementAndDueDay() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "交通银行",
                loanNo = "0004",
                totalPeriods = 3,
                remainingPeriods = 1,
                perPeriodAmount = "5413.34",
                tradeDate = "2026-08-25",
                statementDay = 15,
                dueDayOfMonth = 15,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(LocalDate.of(2026, 9, 15), loan.firstDueDate)
        assertEquals(1, loan.periods.first().index)
        assertEquals(2, loan.paidCount)
        assertEquals(LocalDate.of(2026, 11, 15), loan.nextUnpaid?.dueDate)
        assertEquals(15, loan.statementDay)
        assertEquals(LocalDate.of(2026, 8, 25), loan.tradeDate)
    }

    // 真机 0003：交易 05-17、账单日 15 → 首期 06-15，6 期排到 11-15，已摊销 5 期
    @Test fun sixPeriodPlanStartsFromTheFirstStatementAfterTheTrade() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "交通银行",
                loanNo = "0003",
                totalPeriods = 6,
                remainingPeriods = 1,
                perPeriodAmount = "10999.99",
                tradeDate = "2026-05-17",
                statementDay = 15,
                dueDayOfMonth = 15,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(LocalDate.of(2026, 6, 15), loan.firstDueDate)
        assertEquals(LocalDate.of(2026, 11, 15), loan.periods.last().dueDate)
        assertEquals(5, loan.paidCount)
    }

    // 还款日早于账单日的，账单要到下个月才还；赶得上当月账单的，当期就还
    @Test fun dueDayBeforeStatementDayRollsIntoTheNextMonth() {
        assertEquals(LocalDate.of(2026, 10, 4), LoanAssembler.deriveFirstDue(LocalDate.of(2026, 8, 25), 15, 4))
        assertEquals(LocalDate.of(2026, 9, 4), LoanAssembler.deriveFirstDue(LocalDate.of(2026, 8, 10), 15, 4))
    }

    // 真机借呗：页面上压根没有「X/Y 期」，模型只敢交「总 6 期 + 还剩 2 期」。
    // 末期落在 2026-12-09，正是截图上的合同到期日——这就是不依赖模型的自证。
    @Test fun derivesCurrentPeriodFromRemainingPeriods() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "借呗",
                totalPeriods = 6,
                remainingPeriods = 2,
                perPeriodAmount = "2651.67",
                principalOutstanding = "5169.78",
                dueDayOfMonth = 9,
            ),
            now = LocalDate.of(2026, 10, 20),
        )
        assertEquals("支付宝借呗", loan.institution)
        assertEquals(4, loan.paidCount)
        assertEquals(5, loan.nextUnpaid?.index)
        assertEquals(LocalDate.of(2026, 7, 9), loan.firstDueDate)
        assertEquals(LocalDate.of(2026, 12, 9), loan.periods.last().dueDate)
    }

    // 人当场改过当前期，抄来的剩余期数就该作废——两个数一起决定期号迟早打架
    @Test fun explicitCurrentPeriodWinsOverRemainingPeriods() {
        val loan = LoanAssembler.fromDraft(
            huabei.copy(totalPeriods = 6, currentPeriod = 3, remainingPeriods = 2, nextDueDate = "2026-11-09"),
            now = LocalDate.of(2026, 10, 20),
        )
        assertEquals(3, loan.nextUnpaid?.index)
        assertEquals(2, loan.paidCount)
    }

    // 确认页那格读的是 paidThrough：只给剩余期数时它也得算出 4，否则页面显示 0、入库却是 4 期已还
    @Test fun paidThroughSeesTheDerivedCurrentPeriod() {
        assertEquals(4, InstallmentDraft(institution = "借呗", totalPeriods = 6, remainingPeriods = 2).paidThrough)
    }

    // 真机 0004：账单日 15、还款日 9。交易 08-25 → 09-15 出账 → 10-09 扣第一期，
    // 银行页的「剩余 1 期」是还没出账的那期，不代表已还 2 期。
    @Test fun repaymentDayEarlierThanStatementDayShiftsTheFirstDue() {
        val loan = LoanAssembler.fromDraft(cardDraft(), now = LocalDate.of(2026, 10, 24))
        assertEquals(LocalDate.of(2026, 10, 9), loan.firstDueDate)
        assertEquals(listOf("2026-10-09", "2026-11-09", "2026-12-09"), loan.periods.map { it.dueDate.toString() })
        assertEquals(1, loan.paidCount)
        assertEquals(LocalDate.of(2026, 11, 9), loan.nextUnpaid?.dueDate)
    }

    // 0003 同一个月子：交易 05-17 → 06-15 出账 → 07-09 起算 6 期，到 10 月已还 4 期
    @Test fun sixPeriodCardPlanCountsOnlyPassedDueDates() {
        val loan = LoanAssembler.fromDraft(
            cardDraft().copy(loanNo = "0003", totalPeriods = 6, tradeDate = "2026-05-17"),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(LocalDate.of(2026, 7, 9), loan.firstDueDate)
        assertEquals(LocalDate.of(2026, 12, 9), loan.periods.last().dueDate)
        assertEquals(4, loan.paidCount)
    }

    // 银行口径的「剩余未摊销金额 5,333.33」只含未出账那期本金，不能拿它减剩余应还；
    // 有整笔本金时按比例摊：10,826.68 × 16,000 ÷ 16,240.02 ≈ 10,666.67，息费 160.02
    @Test fun unpaidPrincipalIsProratedFromTheContractAmount() {
        val loan = LoanAssembler.fromDraft(cardDraft(), now = LocalDate.of(2026, 10, 24))
        assertEquals(1_066_667L, loan.unpaidPrincipalCents)
        assertEquals(16_001L, loan.unpaidFeeCents)
        assertFalse(LoanValidator.validateLoan(loan, today = LocalDate.of(2026, 10, 24)).any { it.field == "principalOutstanding" })
    }

    private fun cardDraft() = InstallmentDraft(
        institution = "甘肃农商银行",
        loanNo = "0004",
        totalPeriods = 3,
        remainingPeriods = 1,
        perPeriodAmount = "5413.34",
        principalTotal = "16000.00",
        principalOutstanding = "5333.33",
        tradeDate = "2026-08-25",
        statementDay = 15,
        dueDayOfMonth = 9,
    )

    // 借呗没有账单日：放款日 06-09、还款日 9 号 → 首期 07-09（支用当天太近，顺延一个月），
    // 6 期排到 12-09，今天 10-24 已还 4 期
    @Test fun cashLoanDerivesItsFirstDueFromTheLoanDate() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "借呗",
                totalPeriods = 6,
                remainingPeriods = 2,
                perPeriodAmount = "2651.67",
                principalOutstanding = "5169.78",
                tradeDate = "2026-06-09",
                dueDayOfMonth = 9,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(LocalDate.of(2026, 7, 9), loan.firstDueDate)
        assertEquals(LocalDate.of(2026, 12, 9), loan.periods.last().dueDate)
        assertEquals(4, loan.paidCount)
        assertEquals(LocalDate.of(2026, 11, 9), loan.nextUnpaid?.dueDate)
        assertNull(loan.statementDay)
    }

    // 支用日离最近一个还款日不足 15 天 → 首期顺延；够 15 天 → 当月那个还款日就是首期
    @Test fun cashLoanFirstDueSkipsWhenTheLoanIsTooClose() {
        assertEquals(LocalDate.of(2026, 7, 25), LoanAssembler.deriveFirstDueFromLoanDate(LocalDate.of(2026, 6, 20), 25))
        assertEquals(LocalDate.of(2026, 6, 25), LoanAssembler.deriveFirstDueFromLoanDate(LocalDate.of(2026, 6, 1), 25))
        assertEquals(LocalDate.of(2026, 7, 31), LoanAssembler.deriveFirstDueFromLoanDate(LocalDate.of(2026, 6, 30), 31))
    }

    // 真机反馈：5-17 借款、9 号还款，模型抄了个「账单日 15」进来，
    // 照信用卡规则会算成 6-15 出账、7-09 还款。借呗没有账单日，这一格必须无视。
    @Test fun cashLoanIgnoresACopiedStatementDay() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "支付宝借呗",
                totalPeriods = 6,
                currentPeriod = 5,
                remainingPeriods = 2,
                perPeriodAmount = "17012.73",
                principalTotal = "100000.00",
                principalOutstanding = "16907.89",
                tradeDate = "2026-05-17",
                dueDayOfMonth = 9,
                statementDay = 15,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(LocalDate.of(2026, 6, 9), loan.firstDueDate)
        assertEquals(LocalDate.of(2026, 11, 9), loan.periods.last().dueDate)
        assertNull(loan.statementDay)
        assertEquals(4, loan.paidCount)
    }

    @Test fun editingACashLoanDropsTheStatementDay() {
        val wrong = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "支付宝借呗",
                totalPeriods = 6,
                perPeriodAmount = "17012.73",
                tradeDate = "2026-05-17",
                dueDayOfMonth = 9,
                statementDay = 15,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        // 夹具本身已按借呗规则装配，这里再走一遍编辑保存，确认账单日不会把首期拽回 7-09
        val edited = LoanAssembler.applyEdits(
            wrong, "支付宝借呗", null, 1_701_273L, 1_690_789L, 10_000_000L,
            dueDayOfMonth = 9, statementDay = 15, tradeDate = LocalDate.of(2026, 5, 17),
        )
        assertNull(edited.statementDay)
        assertEquals(LocalDate.of(2026, 6, 9), edited.firstDueDate)
        assertEquals(LocalDate.of(2026, 11, 9), edited.periods.last().dueDate)
    }

    // 明细页给借呗补上放款日：整条计划表跟着重排，已还标记留在原期号上
    @Test fun editingTheLoanDateRelinksACashLoanPlan() {
        val wrong = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "借呗", totalPeriods = 6, remainingPeriods = 2,
                perPeriodAmount = "2651.67", dueDayOfMonth = 1,
            ),
            now = LocalDate.of(2026, 10, 24),
        )
        val fixed = LoanAssembler.applyEdits(
            wrong, "借呗", null, 265_167L, null, null,
            dueDayOfMonth = 9,
            tradeDate = LocalDate.of(2026, 6, 9),
        )
        assertEquals(LocalDate.of(2026, 7, 9), fixed.firstDueDate)
        assertEquals(4, fixed.paidCount)
        assertEquals(LocalDate.of(2026, 11, 9), fixed.nextUnpaid?.dueDate)
    }

    // 等额本金：每期金额、本金、利息都照截图抄，不能用首期金额 × 期数摊
    private fun shanbeiPlan() = InstallmentDraft(
        institution = "支付宝借呗",
        totalPeriods = 3,
        plan = listOf(
            DraftPeriod(1, "2026-10-09", "5413.34", "5333.33", "80.01"),
            DraftPeriod(2, "2026-11-09", "5398.34", "5333.33", "65.01"),
            DraftPeriod(3, "2026-12-09", "5383.34", "5333.34", "50.00"),
        ),
    )

    @Test fun planCopiesDatesAmountsAndPaidFromDueDates() {
        val loan = LoanAssembler.fromDraft(shanbeiPlan(), now = LocalDate.of(2026, 10, 24))
        assertEquals(
            listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 12, 9)),
            loan.periods.map { it.dueDate },
        )
        assertEquals(listOf(541334L, 539834L, 538334L), loan.periods.map { it.amountCents })
        assertEquals(LocalDate.of(2026, 10, 9), loan.firstDueDate)
        assertEquals(1, loan.paidCount)
        assertTrue(loan.amountsVary)
        assertEquals(1619502L, loan.planTotalCents)
        assertEquals(539834L, loan.currentAmountCents)
    }

    @Test fun planPrincipalAndInterestSumIntoUnpaidAmounts() {
        val loan = LoanAssembler.fromDraft(shanbeiPlan(), now = LocalDate.of(2026, 10, 24))
        assertEquals(1600000L, loan.planPrincipalCents)
        assertEquals(1600000L, loan.principalTotalOrNull)
        assertEquals(1066667L, loan.unpaidPrincipalCents)
        assertEquals(11501L, loan.unpaidFeeCents)
        assertEquals(1078168L, loan.remainingPayableCents)
    }

    @Test fun explicitPaidListStillWinsOverPlanDates() {
        val loan = LoanAssembler.fromDraft(
            shanbeiPlan().copy(paidPeriods = listOf(1, 2)),
            now = LocalDate.of(2026, 10, 24),
        )
        assertEquals(2, loan.paidCount)
    }

    @Test fun editingOnlyTheDayKeepsPerPeriodAmounts() {
        val loan = LoanAssembler.fromDraft(shanbeiPlan(), now = LocalDate.of(2026, 10, 24))
        val edited = LoanAssembler.applyEdits(
            loan, "支付宝借呗", null, loan.perPeriodAmountCents, null, null, dueDayOfMonth = 10,
        )
        assertEquals(listOf(541334L, 539834L, 538334L), edited.periods.map { it.amountCents })
        assertEquals(533333L, edited.periods.first().principalCents)
        assertEquals(LocalDate.of(2026, 10, 10), edited.periods.first().dueDate)
    }

    // 人明确改了每期金额，就是要把逐期金额摊平；此时逐期本金/利息必须一起丢掉，否则金额与本金对不上
    @Test fun changingTheAmountFlattensThePlan() {
        val loan = LoanAssembler.fromDraft(shanbeiPlan(), now = LocalDate.of(2026, 10, 24))
        val edited = LoanAssembler.applyEdits(
            loan, "支付宝借呗", null, 540000L, null, null, dueDayOfMonth = 9,
        )
        assertEquals(listOf(540000L, 540000L, 540000L), edited.periods.map { it.amountCents })
        assertTrue(edited.periods.all { it.principalCents == null && it.interestCents == null })
        assertFalse(edited.amountsVary)
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

    // 真机案例：模型把机构名写成「信用卡」，两张不同的卡会并成一笔，下次导入也认不回来
    @Test fun vagueInstitutionWarnsButDoesNotBlock() {
        val findings = LoanValidator.validateDraft(draft().copy(institution = "信用卡"))
        assertEquals(FindingLevel.WARN, findings.first { it.field == "institution" }.level)
        assertFalse(findings.hasError())
    }

    @Test fun namingTheIssuerSilencesTheVagueWarning() {
        assertFalse(LoanValidator.validateDraft(draft().copy(institution = "招商银行")).any { it.field == "institution" })
        assertFalse(LoanValidator.validateLoan(LoanAssembler.fromDraft(draft().copy(institution = "招商银行"))).any { it.field == "institution" })
    }

    @Test fun rejectsPrincipalLargerThanPayable() {
        val loan = LoanAssembler.fromDraft(draft().copy(principalOutstanding = "9,000.00"))
        val finding = LoanValidator.validateLoan(loan).first { it.field == "principalOutstanding" }
        assertEquals(FindingLevel.ERROR, finding.level)
    }

    // 0004/0003 这种卡分期：有交易日期、没有下期还款日，账单日就是首期的唯一依据，缺了必须标红
    @Test fun missingStatementDayBlocksCreditCardDrafts() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "交通银行",
                totalPeriods = 3,
                remainingPeriods = 1,
                perPeriodAmount = "5413.34",
                dueDayOfMonth = 15,
                tradeDate = "2026-08-25",
            ),
        )
        val finding = findings.firstOrNull { it.field == "statementDay" }
        assertNotNull(finding)
        assertEquals(FindingLevel.ERROR, finding!!.level)
    }

    // 借呗这类没有账单日的产品不能被这条卡住：没有交易日期就不触发必填
    @Test fun statementDayIsNotRequiredForLoanAppsWithoutTradeDate() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "借呗",
                totalPeriods = 6,
                remainingPeriods = 2,
                perPeriodAmount = "2651.67",
                principalOutstanding = "5169.78",
                dueDayOfMonth = 9,
            ),
        )
        assertNull(findings.firstOrNull { it.field == "statementDay" && it.level == FindingLevel.ERROR })
    }

    // 借呗页面上的「借款日」会被抄成交易日期，这时也不能因为它没有账单日就标红堵住入库
    @Test fun statementDayIsNotRequiredForCashLoansWithALoanDate() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "借呗",
                totalPeriods = 6,
                remainingPeriods = 2,
                perPeriodAmount = "2651.67",
                principalOutstanding = "5169.78",
                tradeDate = "2026-06-09",
                dueDayOfMonth = 9,
            ),
            today = LocalDate.of(2026, 10, 24),
        )
        assertNull(findings.firstOrNull { it.field == "statementDay" && it.level == FindingLevel.ERROR })
        assertFalse(findings.hasError())
    }

    // 模型给借呗抄了一个「账单日 15」：不拦住入库，但要黄条说清这一格会被忽略
    @Test fun cashLoanWarnsThatACopiedStatementDayIsIgnored() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "支付宝借呗",
                totalPeriods = 6,
                perPeriodAmount = "17012.73",
                tradeDate = "2026-05-17",
                dueDayOfMonth = 9,
                statementDay = 15,
            ),
            today = LocalDate.of(2026, 10, 24),
        )
        assertFalse(findings.hasError())
        val warn = findings.firstOrNull { it.field == "statementDay" }
        assertNotNull(warn)
        assertEquals(FindingLevel.WARN, warn!!.level)
        assertTrue(warn.message.contains("会被忽略"))
    }

    // 但银行分期照样必须填：没有账单日，首期月份只能按放款日猜，猜错整条计划表就歪
    @Test fun statementDayStillRequiredForBanks() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "甘肃农商银行",
                totalPeriods = 3,
                perPeriodAmount = "5413.34",
                tradeDate = "2026-08-25",
                dueDayOfMonth = 9,
            ),
        )
        assertEquals(FindingLevel.ERROR, findings.first { it.field == "statementDay" }.level)
    }

    // 平台表里没有的小贷 App：有放款日就先按现金借款算，黄条提示，不再把人堵在确认页
    @Test fun unknownPlatformWithALoanDateOnlyWarns() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "某某小贷",
                totalPeriods = 12,
                perPeriodAmount = "1200.00",
                tradeDate = "2026-05-17",
                dueDayOfMonth = 9,
            ),
            today = LocalDate.of(2026, 10, 24),
        )
        assertFalse(findings.hasError())
        val warn = findings.first { it.field == "statementDay" }
        assertEquals(FindingLevel.WARN, warn.level)
        assertTrue(warn.message.contains("先按现金借款"))
    }

    // 连放款日都没有就真的推不出首期了，这时仍然要拦住
    @Test fun unknownPlatformWithoutAnyDateBlocks() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "某某小贷",
                totalPeriods = 12,
                perPeriodAmount = "1200.00",
                dueDayOfMonth = 9,
            ),
        )
        assertEquals(FindingLevel.ERROR, findings.first { it.field == "statementDay" }.level)
    }

    // 表里登记过的现金借款（放心借），不需要账单日，也不该冒黄条
    @Test fun knownCashLoanNeedsNoStatementDay() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "抖音放心借",
                totalPeriods = 12,
                perPeriodAmount = "1200.00",
                tradeDate = "2026-05-17",
                dueDayOfMonth = 9,
            ),
            today = LocalDate.of(2026, 10, 24),
        )
        assertNull(findings.firstOrNull { it.field == "statementDay" })
        assertFalse(findings.hasError())
    }

    @Test fun unparsableTradeDateIsAnError() {
        val findings = LoanValidator.validateDraft(draft().copy(tradeDate = "上个月"))
        assertNotNull(findings.firstOrNull { it.field == "tradeDate" && it.level == FindingLevel.ERROR })
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

    // 真机案例：模型把「已摊销期数 2」抄成 paidPeriods:[1,2]，可本期就是第 2 期，钱还没还
    @Test fun draftFlagsPaidListReachingCurrentPeriod() {
        val findings = LoanValidator.validateDraft(draft().copy(currentPeriod = 2, paidPeriods = listOf(1, 2)))
        val finding = findings.first { it.field == "currentPeriod" }
        assertEquals(FindingLevel.WARN, finding.level)
        assertTrue(finding.message.contains("不可能已经还过"))
    }

    // 借呗那类只写「还剩几期」的页面：总期数一旦小于剩余期数，减法会推出第 0 期
    @Test fun draftRejectsRemainingBeyondTotal() {
        val findings = LoanValidator.validateDraft(draft().copy(totalPeriods = 6, currentPeriod = null, remainingPeriods = 9))
        assertEquals(FindingLevel.ERROR, findings.first { it.field == "remainingPeriods" }.level)
    }

    @Test fun draftWarnsWhenRemainingAndCurrentDisagree() {
        val findings = LoanValidator.validateDraft(draft().copy(totalPeriods = 6, currentPeriod = 3, remainingPeriods = 2))
        val finding = findings.first { it.field == "currentPeriod" }
        assertEquals(FindingLevel.WARN, finding.level)
        assertTrue(finding.message.contains("本期该是第 5 期"))
    }

    @Test fun consistentRemainingProducesNoFinding() {
        val findings = LoanValidator.validateDraft(
            draft().copy(totalPeriods = 6, currentPeriod = 5, remainingPeriods = 2, nextDueDate = "2026-11-09", dueDayOfMonth = 9)
        )
        assertFalse(findings.any { it.field == "remainingPeriods" || it.field == "currentPeriod" })
    }

    @Test fun consistentPaidListProducesNoCurrentPeriodFinding() {
        assertFalse(LoanValidator.validateDraft(draft().copy(paidPeriods = listOf(1, 2, 3, 4))).any { it.field == "currentPeriod" })
    }

    // 确认页那格显示的是这个值，跳号时只能算连续前缀，否则会把没还的第 3 期也标成已还
    @Test fun paidThroughCountsOnlyContiguousPrefix() {
        assertEquals(2, draft().copy(paidPeriods = listOf(1, 2, 4)).paidThrough)
        assertEquals(4, draft().copy(paidPeriods = null).paidThrough)
        assertEquals(0, draft().copy(paidPeriods = null, currentPeriod = 1).paidThrough)
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
        assertEquals(FindingLevel.WARN, LoanValidator.validateLoan(loan.copy(status = LoanStatus.ACTIVE)).first { it.message.contains("应改为") }.level)
    }

    // 真机风险：勾完最后一期状态自动翻成已结清，但未还本金还留着导入时抄来的数，
    // 台账上于是出现「已结清、还欠 5333.33」——结清和欠钱同时成立。
    @Test fun flagsSettledLoanThatStillOwesPrincipal() {
        var loan = LoanAssembler.fromDraft(tailDraft(), now = oct20)
        (1..3).forEach { loan = LoanAssembler.togglePaid(loan, it, true, oct20) }
        assertEquals(LoanStatus.SETTLED, loan.status)
        val finding = LoanValidator.validateLoan(loan, today = oct20)
            .first { it.level == FindingLevel.WARN && it.field == "principalOutstanding" }
        assertTrue(finding.message.contains("清零"))
    }

    @Test fun settledWithClearedPrincipalIsClean() {
        var loan = LoanAssembler.fromDraft(tailDraft(), now = oct20)
        (1..3).forEach { loan = LoanAssembler.togglePaid(loan, it, true, oct20) }
        loan = loan.copy(principalOutstandingCents = 0L)
        assertFalse(LoanValidator.validateLoan(loan, today = oct20).any { it.field == "principalOutstanding" })
    }

    // 逐期计划已经带了每期的日期和金额，就不该再逼人补一个「每期金额」「下期还款日」
    @Test fun completePlanNeedsNoSeparateAmountOrDueDate() {
        val findings = LoanValidator.validateDraft(
            InstallmentDraft(
                institution = "支付宝借呗",
                totalPeriods = 2,
                plan = listOf(
                    DraftPeriod(1, "2026-10-09", "5413.34", "5333.33", "80.01"),
                    DraftPeriod(2, "2026-11-09", "5398.34", "5333.33", "65.01"),
                ),
            ),
            today = oct20,
        )
        assertFalse(findings.hasError())
    }

    // 条数对不上总期数时计划会被忽略，必须明说，否则借呗的递减金额会被均摊成首期金额
    @Test fun partialPlanIsWarnedAbout() {
        val findings = LoanValidator.validateDraft(
            draft().copy(
                totalPeriods = 3,
                plan = listOf(DraftPeriod(1, "2026-10-09", "5413.34"), DraftPeriod(2, "2026-11-09", "5398.34")),
            )
        )
        assertTrue(findings.any { it.field == "plan" && it.level == FindingLevel.WARN })
    }

    @Test fun varyingAmountsDoNotTriggerOnePeriodShortWarning() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "支付宝借呗",
                totalPeriods = 3,
                principalOutstanding = "10666.67",
                plan = listOf(
                    DraftPeriod(1, "2026-10-09", "5413.34", "5333.33", "80.01"),
                    DraftPeriod(2, "2026-11-09", "5398.34", "5333.33", "65.01"),
                    DraftPeriod(3, "2026-12-09", "5383.34", "5333.34", "50.00"),
                ),
            ),
            now = oct20,
        )
        assertFalse(LoanValidator.validateLoan(loan, today = oct20).any { it.message.contains("少正好一期") })
    }

    // 甘肃农商真实数据：末期满 2028-01-16，比 21 号早五天。按日期差算 MONTHS.between 会截成 0，
    // 明细页就给一整条正常的分期弹出「相隔 0 个月」，比不报还糟。
    @Test fun lastPeriodDatedAFewDaysEarlyIsNotAMonthGap() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "甘肃农商银行",
                totalPeriods = 3,
                plan = listOf(
                    DraftPeriod(1, "2027-11-21", "1977.43", "1944.44", "32.99"),
                    DraftPeriod(2, "2027-12-21", "1969.18", "1944.44", "24.74"),
                    DraftPeriod(3, "2028-01-16", "1951.75", "1944.60", "7.15"),
                ),
            ),
            now = oct20,
        )
        assertFalse(LoanValidator.validateLoan(loan, today = oct20).any { it.message.contains("不像按月分期") })
    }

    @Test fun twoMonthGapIsStillFlagged() {
        val loan = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "甘肃农商银行",
                totalPeriods = 2,
                plan = listOf(
                    DraftPeriod(1, "2027-11-21", "1977.43", "1944.44", "32.99"),
                    DraftPeriod(2, "2028-01-21", "1970.00", "1944.60", "25.40"),
                ),
            ),
            now = oct20,
        )
        assertTrue(LoanValidator.validateLoan(loan, today = oct20).any { it.message.contains("不像按月分期") })
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

    // 借呗、微粒贷这类现金借款没有账单日，银行/花呗有——确认页据此决定要不要把账单日标红
    @Test fun onlyCashLoansGoWithoutAStatementDay() {
        assertTrue(LoanIdentity.hasNoStatementDay("借呗"))
        assertTrue(LoanIdentity.hasNoStatementDay("微粒贷"))
        assertTrue(LoanIdentity.hasNoStatementDay("网商贷"))
        assertFalse(LoanIdentity.hasNoStatementDay("花呗"))
        assertFalse(LoanIdentity.hasNoStatementDay("交通银行"))
        assertFalse(LoanIdentity.hasNoStatementDay(null))
    }

    // 抖音放心借 / 美团借钱 是现金借款，和同 App 里的月付是两个产品：
    // 别名表里曾有过「抖音→抖音月付」这种短名，包含匹配会把两者并成一条账
    @Test fun siblingProductsDoNotCollapseIntoOnePlatform() {
        assertEquals("抖音放心借", LoanIdentity.normalizeInstitution("抖音放心借"))
        assertEquals("美团借钱", LoanIdentity.normalizeInstitution("美团借钱"))
        assertEquals("抖音月付", LoanIdentity.normalizeInstitution("抖音月付"))
        assertEquals("美团月付", LoanIdentity.normalizeInstitution("美团月付 · 分期"))
    }

    // 表里每一条写法都必须落到它自己的标准名。任何一条被别的平台吞掉，都会静默并账
    @Test fun everyPlatformNameResolvesToItsOwnCanonical() {
        Platforms.all.forEach { spec ->
            (listOf(spec.canonical) + spec.aliases).forEach { name ->
                assertEquals(name, spec.canonical, LoanIdentity.normalizeInstitution(name))
            }
        }
    }

    @Test fun statementKindComesFromThePlatformTable() {
        assertEquals(LoanIdentity.StatementKind.CASH_LOAN, LoanIdentity.statementKind("抖音放心借"))
        assertEquals(LoanIdentity.StatementKind.CASH_LOAN, LoanIdentity.statementKind("借呗"))
        assertEquals(LoanIdentity.StatementKind.BILLING, LoanIdentity.statementKind("美团月付"))
        assertEquals(LoanIdentity.StatementKind.UNKNOWN, LoanIdentity.statementKind("某某小贷"))
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

    // 借呗同平台多笔、页面没有单号：机构/金额/还款日/期数全撞上时，只能靠放款日区分
    private fun cashLoan(tradeDate: String, principalTotal: String) = LoanAssembler.fromDraft(
        InstallmentDraft(
            institution = "借呗",
            totalPeriods = 6,
            perPeriodAmount = "2651.67",
            dueDayOfMonth = 9,
            tradeDate = tradeDate,
            principalTotal = principalTotal,
        )
    )

    @Test fun differentTradeDateBreaksTheWeakMatch() {
        val existing = cashLoan("2026-05-17", "100000.00")
        val match = matchLoan(
            listOf(existing), "借呗", null, 265167L, 9, 6,
            tradeDate = LocalDate.of(2026, 6, 9), principalTotalCents = 10_000_000L,
        )
        assertTrue(match is DraftMatch.Unknown)
    }

    @Test fun differentPrincipalBreaksTheWeakMatch() {
        val existing = cashLoan("2026-05-17", "100000.00")
        val match = matchLoan(
            listOf(existing), "借呗", null, 265167L, 9, 6,
            tradeDate = LocalDate.of(2026, 5, 17), principalTotalCents = 5_000_000L,
        )
        assertTrue(match is DraftMatch.Unknown)
    }

    @Test fun sameTradeAndPrincipalStaysSuspect() {
        val existing = cashLoan("2026-05-17", "100000.00")
        val match = matchLoan(
            listOf(existing), "借呗", null, 265167L, 9, 6,
            tradeDate = LocalDate.of(2026, 5, 17), principalTotalCents = 10_000_000L,
        )
        assertTrue(match is DraftMatch.Suspect)
    }

    // 老记录没补放款日/借款金额时不能一票否决，否则同一平台的下张截图会变成新债
    @Test fun missingTradeDateDoesNotBlockTheWeakMatch() {
        val existing = cashLoan("2026-05-17", "100000.00")
        assertTrue(matchLoan(listOf(existing), "借呗", null, 265167L, 9, 6) is DraftMatch.Suspect)

        val legacy = LoanAssembler.fromDraft(
            InstallmentDraft(
                institution = "借呗", totalPeriods = 6, perPeriodAmount = "2651.67", dueDayOfMonth = 9,
            )
        )
        val match = matchLoan(
            listOf(legacy), "借呗", null, 265167L, 9, 6,
            tradeDate = LocalDate.of(2026, 5, 17), principalTotalCents = 10_000_000L,
        )
        assertTrue(match is DraftMatch.Suspect)
    }

    // 等额本金这类每期金额逐月变，下个月弱键必然落空；平台+放款日+借款金额不变，靠它认回同一笔
    @Test fun stableKeyMatchesNextMonthWhenAmountDrifts() {
        val existing = cashLoan("2026-05-17", "100000.00")
        val match = matchLoan(
            listOf(existing), "借呗", null, 2496667L, 9, 6,
            tradeDate = LocalDate.of(2026, 5, 17), principalTotalCents = 10_000_000L,
        )
        assertTrue(match is DraftMatch.Suspect)
    }

    // 同一个借呗下几笔不同的借款，放款日和金额是唯一的区分手段，稳定键不许把它们并成一条
    @Test fun stableKeyKeepsSiblingBorrowsApart() {
        val siblings = listOf(cashLoan("2026-05-17", "100000.00"), cashLoan("2026-06-09", "15000.00"))
        val match = matchLoan(
            siblings, "借呗", null, 282897L, 9, 6,
            tradeDate = LocalDate.of(2026, 8, 9), principalTotalCents = 1_600_000L,
        )
        assertTrue(match is DraftMatch.Unknown)
    }

    // 「信用卡」「贷款」这种名字等于没写，下个月同一张卡会被判成新债
    @Test fun vagueInstitutionStillNeedsTheIssuer() {
        assertTrue(LoanIdentity.isVagueInstitution("信用卡"))
        assertTrue(LoanIdentity.isVagueInstitution(" 贷款 "))
        assertFalse(LoanIdentity.isVagueInstitution("招商银行"))
        assertFalse(LoanIdentity.isVagueInstitution("花呗"))
        assertFalse(LoanIdentity.isVagueInstitution(null))
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
