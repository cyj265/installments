package com.cyj265.installments.domain

import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import com.cyj265.installments.data.Period
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 把确认页上的草稿摊成完整期数计划表。
 *
 * 只从截图读「当前期 + 下一期日期 + 总期数」三个量，其余期数全部推算——
 * 逐期读取既慢又容易错，且推算结果一旦和截图上的总期数或本金对不上，[LoanValidator] 就会抓到。
 */
object LoanAssembler {

    fun fromDraft(
        draft: InstallmentDraft,
        now: LocalDate = LocalDate.now(),
        id: String = UUID.randomUUID().toString(),
    ): Loan {
        val total = draft.totalPeriods ?: 0
        // 卡分期的「剩余期数」是还没出账的期数，不是没还的期数：按还款日算，0004 今天只还过一期。
        // 人显式填了当前期才算了话；否则优先看有哪些期的还款日已经过去。
        val current = (draft.currentPeriodOn(now) ?: draft.currentPeriodOrDerived ?: 1).coerceAtLeast(1)
        // 逐期计划完整时整条照抄：借呗、微粒贷是等额本金，每期金额和利息都不同，
        // 用「每期金额 × 期数」会同时算错应还合计和本金。perPeriod 只留首期金额做标称值。
        // 页面上没有应还日这一列时（交行），金额和本息照抄、日期按首期 + 每月推。
        val datedPlan = draft.completePlan
        val plan = datedPlan ?: draft.planWithoutDates
        val perPeriod = plan?.firstOrNull()?.let { Money.parseCents(it.amount) }
            ?: Money.parseCents(draft.perPeriodAmount) ?: 0L
        val nextDue = draft.nextDueDateParsed
        // 借呗、微粒贷这类现金借款没有账单日这道手续。模型有时会从别处抄一个「账单日 15」进来，
        // 照信用卡规则算就会把 5-17 借款、9 号还款的第一期推成 7-09；这类机构一律当没填。
        val statementDay = draft.statementDay?.takeUnless { LoanIdentity.hasNoStatementDay(draft.institution) }
        val tradeDate = draft.tradeDateParsed
        val explicitDueDay = draft.dueDayOfMonth
            ?: nextDue?.dayOfMonth
            ?: plan?.firstOrNull()?.dueDateParsed?.dayOfMonth
        val dueDay = explicitDueDay ?: 1
        // 三个日期齐全时首期是算出来的，不依赖今天是几号：交易 → 下一个账单日出账 → 账单对应的还款日。
        // 缺交易日期或账单日就只能退回到「从今天往回倒推」，月份对不对全看运气。
        val firstDue = datedPlan?.firstOrNull()?.dueDateParsed ?: when {
            nextDue != null -> nextDue.minusMonths((current - 1).toLong())
            tradeDate != null && statementDay != null && explicitDueDay != null ->
                deriveFirstDue(tradeDate, statementDay, explicitDueDay)
            // 借呗这类现金借款没有账单日，首期直接从放款日和还款日推
            tradeDate != null && explicitDueDay != null ->
                deriveFirstDueFromLoanDate(tradeDate, explicitDueDay)
            else -> {
                // 只给了「每月几号」时，把本期锚在最近一个还没到的那天，再往前推回第 1 期。
                // 直接用本月那天，一旦还款日已经过去，入库第一格就是逾期。
                val thisMonth = now.withDayOfMonth(dueDay.coerceIn(1, 28))
                val anchor = if (thisMonth.isBefore(now)) thisMonth.plusMonths(1) else thisMonth
                anchor.minusMonths((current - 1).toLong())
            }
        }

        // 空数组要当成"模型没读到"而不是"确实一期未还"：提示词让模型把不确定的字段填 null，
        // 但它对列表往往直接回 []，此时若照单收下，已还进度会被清零，而 currentPeriod 明明写着第 5 期。
        val explicitPaid = draft.paidPeriods?.toSet()?.takeIf { it.isNotEmpty() }
        val periods = if (plan != null) {
            plan.mapIndexed { i, p ->
                Period(
                    index = i + 1,
                    dueDate = p.dueDateParsed ?: firstDue.plusMonths(i.toLong()),
                    amountCents = Money.parseCents(p.amount)!!,
                    paid = explicitPaid?.contains(i + 1) ?: (i + 1 < current),
                    principalCents = Money.parseCents(p.principal),
                    interestCents = Money.parseCents(p.interest),
                )
            }
        } else {
            (1..total).map { i ->
                Period(
                    index = i,
                    dueDate = firstDue.plusMonths((i - 1).toLong()),
                    amountCents = perPeriod,
                    paid = explicitPaid?.contains(i) ?: (i < current),
                )
            }
        }
        val settled = total > 0 && periods.isNotEmpty() && periods.all { it.paid }

        return Loan(
            id = id,
            institution = LoanIdentity.normalizeInstitution(draft.institution),
            loanNo = draft.loanNo?.trim()?.takeIf { it.isNotEmpty() },
            totalPeriods = total,
            perPeriodAmountCents = perPeriod,
            dueDayOfMonth = dueDay,
            firstDueDate = firstDue,
            statementDay = statementDay,
            tradeDate = tradeDate,
            principalOutstandingCents = Money.parseCents(draft.principalOutstanding),
            principalTotalCents = Money.parseCents(draft.principalTotal),
            status = if (settled) LoanStatus.SETTLED else LoanStatus.ACTIVE,
            note = draft.note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = Instant.now(),
            periods = periods,
        )
    }

    /**
     * 明细页就地订正：机构名、单号、两个金额口径、还款日。
     *
     * 还款日只挪日号、不挪月份：识别时没读到还款日会退化成「每月 1 日」，
     * 改成人真正知道的日号后，整条计划表按新日号重排，已还标记仍挂在自己的期号上。
     * 总期数不在这里改——那才是会让已还标记整体错位、且没有截图数字能自证的一项。
     */
    fun applyEdits(
        loan: Loan,
        institution: String,
        loanNo: String?,
        perPeriodAmountCents: Long,
        principalOutstandingCents: Long?,
        principalTotalCents: Long?,
        dueDayOfMonth: Int? = null,
        statementDay: Int? = null,
        tradeDate: LocalDate? = loan.tradeDate,
    ): Loan {
        val institutionName = LoanIdentity.normalizeInstitution(institution)
        val day = dueDayOfMonth?.coerceIn(1, 31) ?: loan.dueDayOfMonth
        // 改成借呗这类现金借款时，旧的账单日要一起丢掉，否则首期还会按信用卡的出账规则算
        val statement = if (LoanIdentity.hasNoStatementDay(institutionName)) null
        else statementDay?.coerceIn(1, 31) ?: loan.statementDay
        // 交易日期和两个日子齐全时按信用卡的出账规则重算首期，整条计划表的月份也跟着重排；
        // 没有账单日（借呗这类）就按放款日推首期；两者都没有时只能挪日号，月份保持原样。
        val derived = tradeDate?.let { trade ->
            if (statement != null) deriveFirstDue(trade, statement, day) else deriveFirstDueFromLoanDate(trade, day)
        }
        // 只改日期时逐期金额原样保留：等额本金的记录被摊平成同一个数就再也算不回去了。
        // 人真的改了「每期金额」才是明确要摊平，此时逐期本金/利息也一并丢掉，免得和金额对不上。
        val amountChanged = perPeriodAmountCents != loan.perPeriodAmountCents
        val periods = loan.periods.map {
            val dueDate = if (derived != null) derived.plusMonths((it.index - 1).toLong()) else it.dueDate.withDay(day)
            if (amountChanged) {
                it.copy(amountCents = perPeriodAmountCents, dueDate = dueDate, principalCents = null, interestCents = null)
            } else {
                it.copy(dueDate = dueDate)
            }
        }
        return loan.copy(
            institution = institutionName,
            loanNo = loanNo?.trim()?.takeIf { it.isNotEmpty() },
            perPeriodAmountCents = perPeriodAmountCents,
            principalOutstandingCents = principalOutstandingCents,
            principalTotalCents = principalTotalCents,
            dueDayOfMonth = day,
            statementDay = statement,
            tradeDate = tradeDate,
            firstDueDate = periods.firstOrNull()?.dueDate ?: loan.firstDueDate,
            periods = periods,
            updatedAt = Instant.now(),
        )
    }

    /**
     * 信用卡分期的首期还款日：交易后遇到第一个账单日出账，再按还款日扣款。
     * 还款日早于账单日的，当期账单要到下个月才还；同一天或更晚的，当期就是还款日。
     */
    internal fun deriveFirstDue(tradeDate: LocalDate, statementDay: Int, dueDay: Int): LocalDate {
        val statementMonth = YearMonth.from(tradeDate).let {
            if (tradeDate.dayOfMonth <= statementDay) it else it.plusMonths(1)
        }
        val statement = statementMonth.atDay(statementDay.coerceAtMost(statementMonth.lengthOfMonth()))
        val dueMonth = if (dueDay >= statementDay) YearMonth.from(statement) else YearMonth.from(statement).plusMonths(1)
        return dueMonth.atDay(dueDay.coerceAtMost(dueMonth.lengthOfMonth()))
    }

    /**
     * 现金借款（借呗、微粒贷这类）没有账单日：放款后第一个还款日就是首期。
     * 但支用日离最近一个还款日不足 15 天时，借呗不收当期，首期顺延到下个月——
     * 放款日当天那个还款日反而算「太近」，会排到下个月同日。
     */
    internal fun deriveFirstDueFromLoanDate(tradeDate: LocalDate, dueDay: Int): LocalDate {
        val month = YearMonth.from(tradeDate)
        val candidate = month.atDay(dueDay.coerceAtMost(month.lengthOfMonth()))
        val first = if (ChronoUnit.DAYS.between(tradeDate, candidate) < 15L) candidate.plusMonths(1) else candidate
        return first.withDay(dueDay)
    }

    /** 日号超过当月天数时落到月末（2 月没有 31 日），而不是把整笔账跳到下个月。 */
    private fun LocalDate.withDay(day: Int): LocalDate = withDayOfMonth(day.coerceAtMost(lengthOfMonth()))

    /** 标记某一期的已还状态，同时维护「全部还完即结清」。 */
    fun togglePaid(loan: Loan, periodIndex: Int, paid: Boolean, on: LocalDate): Loan {
        val periods = loan.periods.map { if (it.index == periodIndex) it.copy(paid = paid, paidOn = if (paid) on else null) else it }
        val settled = periods.isNotEmpty() && periods.all { it.paid }
        return loan.copy(
            periods = periods,
            status = if (settled) LoanStatus.SETTLED else LoanStatus.ACTIVE,
            updatedAt = Instant.now(),
        )
    }

    /** 提前结清：剩余期全部置已还，本金口径清零；总额能推出来的先固化下来，结清后仍看得到总计。 */
    fun settleEarly(loan: Loan, on: LocalDate): Loan = loan.copy(
        periods = loan.periods.map { it.copy(paid = true, paidOn = it.paidOn ?: on) },
        principalTotalCents = loan.principalTotalOrNull,
        principalOutstandingCents = 0L,
        status = LoanStatus.SETTLED,
        updatedAt = Instant.now(),
    )
}

/** 有交易日期和还款日就能推首期：卡分期按账单日出账，借呗这类没有账单日的按放款日推。 */
fun InstallmentDraft.derivedFirstDue(): LocalDate? {
    val trade = tradeDateParsed ?: return null
    val dueDay = dueDayOfMonth ?: return null
    val statement = statementDay?.takeUnless { LoanIdentity.hasNoStatementDay(institution) }
    return if (statement != null) LoanAssembler.deriveFirstDue(trade, statement, dueDay)
    else LoanAssembler.deriveFirstDueFromLoanDate(trade, dueDay)
}

/**
 * 当前所处期号。人填过就用人的；否则按还款日数：已经到期过的期数就是已还期数。
 *
 * 不能用「总期数 − 剩余期数 + 1」：卡分期页的「剩余期数」是还没出账的期数，
 * 0004 显示剩余 1 期，按减法会得出第 3 期（12-09 未来），把没还的一期标成已还。
 */
fun InstallmentDraft.currentPeriodOn(today: LocalDate): Int? {
    currentPeriod?.let { return it }
    val total = totalPeriods ?: return null
    if (total <= 0) return null
    // 逐期计划里有真实应还日就按它数；没有才用「首期 + 每个月」推
    val dates = completePlan?.mapNotNull { it.dueDateParsed }
        ?: derivedFirstDue()?.let { first -> (1..total).map { first.plusMonths((it - 1).toLong()) } }
        ?: return null
    val passed = dates.count { !it.isAfter(today) }
    return (passed + 1).coerceIn(1, total)
}

/** 连续已还到第几期，口径和 [LoanAssembler.fromDraft] 入库时保持一致。 */
fun InstallmentDraft.paidThroughOn(today: LocalDate): Int {
    val explicit = paidPeriods?.toSet()?.takeIf { it.isNotEmpty() }
    if (explicit != null) {
        var n = 0
        while (explicit.contains(n + 1)) n++
        return n
    }
    return ((currentPeriodOn(today) ?: currentPeriodOrDerived ?: 1) - 1).coerceAtLeast(0)
}
