package com.cyj265.installments.domain

import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import com.cyj265.installments.domain.LoanIdentity.StatementKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class FindingLevel { ERROR, WARN }

/**
 * [field] 对应确认页上的输入框，界面据此把出错字段标红；为 null 表示整条记录级别的问题。
 */
data class Finding(val level: FindingLevel, val field: String?, val message: String)

/**
 * 识别环节一定会出错，区别只在于有没有被发现。这里的规则就是「被发现」的部分：
 * 每条都只依赖截图上能同时读到的数字之间的关系，任何一个不成立就说明有一次读取是错的。
 */
object LoanValidator {

    fun validateDraft(draft: InstallmentDraft, today: LocalDate = LocalDate.now()): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (draft.institution.isBlank()) {
            findings += error("institution", "机构名为空，无法归档")
        } else if (LoanIdentity.isVagueInstitution(draft.institution)) {
            findings += warn(
                "institution",
                "机构名「${draft.institution.trim()}」太笼统：所有信用卡都会并成同一笔。" +
                    "改成发卡行（如「招商银行」），下个月的新截图才认得出是同一张卡",
            )
        }
        val total = draft.totalPeriods
        if (total == null) {
            findings += error("totalPeriods", "没读到总期数")
        } else if (total !in 1..360) {
            findings += error("totalPeriods", "总期数 $total 超出合理范围（1–360）")
        }
        // 逐期计划完整时每期金额本身就有，不再要求一个统一的「每期金额」
        val plan = draft.completePlan
        // 日期整列都空的计划（页面没有应还日那一栏）照样能用：金额、本息照抄，日期拿首期往后推。
        // 但它解决不了「首期从哪个月开始」，所以下面跟日期有关的几项仍然只认带日期的计划。
        val amountPlan = plan ?: draft.planWithoutDates
        if (Money.parseCents(draft.perPeriodAmount) == null && amountPlan == null) {
            findings += error("perPeriodAmount", "每期金额「${draft.perPeriodAmount ?: "空"}」无法解析成数字")
        }
        val rawPlan = draft.plan
        if (!rawPlan.isNullOrEmpty() && amountPlan == null) {
            findings += warn(
                "plan",
                "截图里的逐期计划有 ${rawPlan.size} 条，和总期数 ${draft.totalPeriods ?: "?"} 对不上（或有日期、金额读不出来），" +
                    "入库时会忽略它、按每期金额均摊——借呗这类逐期金额不同的记录会因此算不准"
            )
        }
        // 借款本金是整笔的钱，按定义不可能超过「每期应还 × 总期数」；超过了就是两个数里有一个抄错
        val principalTotalText = draft.principalTotal?.trim()
        if (!principalTotalText.isNullOrEmpty()) {
            val totalPrincipal = Money.parseCents(principalTotalText)
            val perPeriod = Money.parseCents(draft.perPeriodAmount)
            val plannedTotal = amountPlan?.sumOf { Money.parseCents(it.amount)!! }
            if (totalPrincipal == null) {
                findings += error("principalTotal", "借款本金「$principalTotalText」无法解析成数字")
            } else if (plannedTotal != null && totalPrincipal > plannedTotal) {
                findings += error(
                    "principalTotal",
                    "借款本金 ${Money.format(totalPrincipal)} 比逐期计划合计 ${Money.format(plannedTotal)} 还多，两者至少有一个抄错了"
                )
            } else if (plannedTotal == null && perPeriod != null && total != null && totalPrincipal > perPeriod * total) {
                findings += error(
                    "principalTotal",
                    "借款本金 ${Money.format(totalPrincipal)} 比 $total 期 × 每期 ${Money.format(perPeriod)} = ${Money.format(perPeriod * total)} 还多，两者至少有一个抄错了"
                )
            }
        }
        val current = draft.currentPeriod
        if (current != null && total != null && current > total) {
            findings += error("currentPeriod", "当前期 $current 大于总期数 $total")
        }
        // 借呗这类页面上没有「X/Y 期」，只写「还剩几期」。App 用 总−剩余+1 推当前期，
        // 所以这两个数一旦同时出现又不自洽，装配出来的计划表会整体错位。
        val left = draft.remainingPeriods
        if (left != null && total != null) {
            if (left > total) {
                findings += error("remainingPeriods", "剩余期数 $left 大于总期数 $total，两者不可能同时成立")
            } else if (current != null && left >= 1 && current != total - left + 1 && draft.derivedFirstDue() == null) {
                // 有交易日期+账单日+还款日时，当前期按还款日算，卡分期的「剩余期数」又是未出账期数，
                // 总−剩余+1 在这里本来就不成立，不能拿来报警。
                findings += warn(
                    "currentPeriod",
                    "总 $total 期、剩余 $left 期，本期该是第 ${total - left + 1} 期，可当前期填的是 $current。" +
                        "改「当前期」或「剩余期数」任意一格，改过的那一格为准"
                )
            }
        }
        // 银行页面上的「已摊销期数」是账单口径，模型常照抄成 paidPeriods；而 paidPeriods 非空时
        // 会压过当前期，两者一矛盾，整条计划表就整体错位——第 2 期还没到却被标成已还就是这么来的。
        val paidThrough = draft.paidThroughOn(today)
        val effectiveCurrent = draft.currentPeriodOn(today) ?: draft.currentPeriodOrDerived
        if (effectiveCurrent != null && draft.paidPeriods?.isNotEmpty() == true && paidThrough >= effectiveCurrent) {
            findings += warn(
                "currentPeriod",
                "已还期号里含第 $paidThrough 期，但当前期是第 $effectiveCurrent 期——本期不可能已经还过。" +
                    "改「当前期」或「已还到第几期」任意一格，改过的那一格为准"
            )
        }
        val dueDate = draft.nextDueDateParsed
        // 逐期计划里每期都有完整应还日，日期已经照着截图抄了，不必再要求人另填一个下期还款日
        val day = draft.dueDayOfMonth ?: plan?.firstOrNull()?.dueDateParsed?.dayOfMonth
        val rawDueDate = draft.nextDueDate?.trim()
        if (dueDate == null && !rawDueDate.isNullOrEmpty()) {
            findings += error("nextDueDate", "还款日「$rawDueDate」读不出日期，改成 YYYY-MM-DD，或清空它、只填「还款日」的每月几号")
        } else if (dueDate == null && day == null && plan == null) {
            // 交行这类分期详情页压根不显示还款日，只有账单日固定这件事是确定的。
            // 只认完整日期会把人逼去猜一个 YYYY-MM-DD，而「每月 25 日」才是他真正知道的东西。
            findings += error("nextDueDate", "没读到还款日。截图上没有也没关系，在「还款日」里填每月几号即可，剩余期数会按月推算")
        }
        if (day != null && day !in 1..31) {
            findings += error("dueDayOfMonth", "还款日 $day 不在 1–31 之间")
        } else if (day != null && dueDate != null && day != dueDate.dayOfMonth) {
            findings += warn("dueDayOfMonth", "还款日填的是每月 $day 日，但识别到的日期是 $dueDate（${dueDate.dayOfMonth} 日）")
        }
        // 账单日和还款日是两个日子：分期先按账单日出账，再按还款日扣款。
        // 只要有交易日期（说明是信用卡分期这类账单型记录），缺账单日就必须标红——
        // 没有它首期月份只能按今天猜，猜错了整条计划表跟着歪。
        val trade = draft.tradeDateParsed
        val rawTrade = draft.tradeDate?.trim()
        if (trade == null && !rawTrade.isNullOrEmpty()) {
            findings += error("tradeDate", "交易日期「$rawTrade」读不出日期，改成 YYYY-MM-DD，或清空它")
        }
        val statement = draft.statementDay
        val kind = LoanIdentity.statementKind(draft.institution)
        // 模型有时会从页面别处抄一个账单日进来；借呗按信用卡规则算会把首期月份推错，
        // 所以这里不是让它改，而是明说这格会被忽略。
        if (statement != null && kind == StatementKind.CASH_LOAN) {
            findings += warn(
                "statementDay",
                "借呗、微粒贷这类现金借款没有账单日，这一格会被忽略：首期直接按放款日 " +
                    "${trade ?: "（未填）"} 和每月 ${draft.dueDayOfMonth ?: "?"} 日推" +
                    "（支用日离还款日不足 15 天时顺延一个月）"
            )
        }
        if (statement != null && statement !in 1..31 && kind != StatementKind.CASH_LOAN) {
            findings += error("statementDay", "账单日 $statement 不在 1–31 之间")
        } else if (statement == null && dueDate == null && plan == null && kind != StatementKind.CASH_LOAN) {
            // 平台表里没有的，不能因为「缺账单日」就把人堵在确认页：有放款日就按现金借款先算，
            // 人看到黄条自然会判断这到底是不是信用卡，是的话补上账单日，首期月份跟着重算。
            when {
                kind == StatementKind.BILLING && trade != null -> findings += error(
                    "statementDay",
                    "没读到账单日。分期先在账单日出账、再在还款日扣款，两个日子都填上才能算出首期还款日" +
                        "（比如账单日 15、还款日 15，首期就是交易后第一个 15 号）",
                )
                // 连交易日期都没有时，账单日也用不上：首期只能从今天往回倒推，不必拦人
                kind == StatementKind.BILLING -> Unit
                trade != null -> findings += warn(
                    "statementDay",
                    "没读到账单日，先按现金借款算：首期 = 放款日 $trade ${
                        draft.dueDayOfMonth?.let { "之后第一个 $it 号" } ?: "之后第一个还款日（还款日还没填）"
                    }（离还款日不足 15 天顺延一个月）。" +
                        "如果这其实是先出账、后还款的产品（信用卡、花呗这类），把账单日填上，首期月份会跟着改",
                )
                else -> findings += error(
                    "statementDay",
                    "既没有账单日、也没有放款日，推不出首期：信用卡分期填账单日，现金借款填放款日，二选一",
                )
            }
        }
        if (statement != null && trade == null && rawTrade.isNullOrEmpty() && dueDate == null && plan == null) {
            findings += warn("tradeDate", "没有分期交易日期，首期只能从今天往回倒推；填上交易日期（YYYY-MM-DD）能算准月份")
        }
        val currentByDate = draft.currentPeriodOn(today)
        if (total != null && currentByDate != null && currentByDate == total && dueDate != null && dueDate.isBefore(today)) {
            findings += warn("currentPeriod", "末期且日期已过，确认这笔是否其实已经结清")
        }
        return findings
    }

    fun validateLoan(loan: Loan, today: LocalDate = LocalDate.now()): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (LoanIdentity.isVagueInstitution(loan.institution)) {
            findings += warn(
                "institution",
                "机构名「${loan.institution}」太笼统：在明细页改成发卡行（如「招商银行」），下个月的新截图才认得出是同一张卡",
            )
        }
        if (loan.perPeriodAmountCents <= 0L || loan.periods.any { it.amountCents <= 0L }) {
            findings += error("perPeriodAmount", "每期金额必须大于 0")
        }
        if (loan.dueDayOfMonth !in 1..31) {
            findings += error("dueDayOfMonth", "还款日 ${loan.dueDayOfMonth} 不在 1–31 之间")
        }
        loan.statementDay?.let {
            if (it !in 1..31) findings += error("statementDay", "账单日 $it 不在 1–31 之间")
        }
        if (loan.totalPeriods < 1) {
            findings += error("totalPeriods", "总期数必须至少为 1")
        }
        if (loan.periods.size != loan.totalPeriods) {
            findings += error("totalPeriods", "总期数 ${loan.totalPeriods} 与计划表期数 ${loan.periods.size} 不一致")
        }
        val expected = (1..loan.totalPeriods).toSet()
        val actual = loan.periods.map { it.index }.toSet()
        if (expected != actual) {
            val missing = (expected - actual).sorted()
            val extra = (actual - expected).sorted()
            val detail = buildString {
                if (missing.isNotEmpty()) append(" 缺第 ${missing.joinToString("、")} 期")
                if (extra.isNotEmpty()) append(" 多出第 ${extra.joinToString("、")} 期")
            }
            findings += error(null, "期号不连续$detail")
        }

        // 已还期号必须是 1..n 的前缀，否则说明中间漏记了一期
        val firstUnpaid = loan.periods.filter { !it.paid }.minOfOrNull { it.index }
        val lastPaid = loan.periods.filter { it.paid }.maxOfOrNull { it.index }
        if (firstUnpaid != null && lastPaid != null && lastPaid > firstUnpaid) {
            val holes = (firstUnpaid until lastPaid).filter { i -> loan.periods.any { it.index == i && !it.paid } }
            findings += warn(null, "第 $firstUnpaid 期未标记已还，但第 $lastPaid 期已还，中间第 ${holes.joinToString("、")} 期可能漏记")
        }

        // 「已还」的期排在今天之后，只可能是「当前期」和「下期还款日」互相矛盾：
        // 计划表是从下期往回倒推的，两个输入对不上，整条时间轴就整体错位。
        val futurePaid = loan.periods.filter { it.paid && it.dueDate.isAfter(today) }
        if (futurePaid.isNotEmpty()) {
            findings += warn(
                "nextDueDate",
                "第 ${futurePaid.joinToString("、") { "${it.index} 期（${it.dueDate}）" }} 标成已还，但应还日还没到。" +
                    "「当前期」和「下期还款日」对不上，计划表被整体倒推错位了——把「下期还款日」清空、只留每月几号，让 App 重新推"
            )
        }

        val sorted = loan.periods.sortedBy { it.index }
        for (i in 1 until sorted.size) {
            // 按「年-月」比，不按日期差：末期常被银行提前几天（12-21 → 01-16），
            // 用 LocalDate 算 MONTHS.between 会截断成 0 个月，把正常的一期报成异常。
            val months = ChronoUnit.MONTHS.between(
                YearMonth.from(sorted[i - 1].dueDate),
                YearMonth.from(sorted[i].dueDate),
            )
            if (months != 1L) {
                findings += warn("nextDueDate", "第 ${sorted[i - 1].index} 期到第 ${sorted[i].index} 期相隔 $months 个月，不像按月分期")
                break
            }
        }

        val principal = loan.principalOutstandingCents
        if (principal != null) {
            if (principal < 0L) {
                findings += error("principalOutstanding", "未还本金不能为负")
            } else if (loan.remainingPayableCents > 0 && principal > loan.remainingPayableCents) {
                findings += error(
                    "principalOutstanding",
                    "未还本金 ${Money.format(principal)} 大于剩余应还合计 ${Money.format(loan.remainingPayableCents)}，两者不可能同时成立"
                )
            } else {
                // 卡分期的「剩余未摊销本金」只含还没出账的那一期，和剩余应还不同口径，
                // 直接相减会凭空冒出一大块息费；有整笔本金时按比例摊出待还本金再比。
                val comparable = if (loan.statementDay != null) loan.unpaidPrincipalCents ?: principal else principal
                val gap = loan.remainingPayableCents - comparable
                // 逐期金额不同的（借呗等额本金），「差正好一期」没有意义：每期本来就不是同一个数
                if (!loan.amountsVary && gap == loan.perPeriodAmountCents && gap > 0) {
                    findings += warn(
                        "principalOutstanding",
                        "未还本金比剩余应还合计少正好一期（${Money.format(gap)}），很可能把「剩余期数」读少了一期"
                    )
                }
                // 只剩一期时「未还本金 ≈ 单期金额」本来就成立；16000÷3 那种差 1 分的尾差也不该弹黄条。
                // 卡分期的未摊销本金按定义就小于一期，这条只对按待还口径记账的贷款生效。
                if (loan.statementDay == null && !loan.amountsVary && loan.unpaidPeriods.size > 1 &&
                    principal in 1 until (loan.perPeriodAmountCents - 100L)
                ) {
                    findings += warn("principalOutstanding", "未还本金 ${Money.format(principal)} 比单期金额还小，像是把尾期金额当成了本金")
                }
            }
        }

        if (loan.status == LoanStatus.SETTLED && loan.principalOutstandingCents != null && loan.principalOutstandingCents > 0L) {
            findings += warn("principalOutstanding", "已结清但未还本金还写着 ${Money.format(loan.principalOutstandingCents)}，应该清零或清空")
        }
        if (loan.status == LoanStatus.ACTIVE && loan.unpaidPeriods.isEmpty()) {
            findings += warn(null, "所有期都已标记已还，应改为「已结清」状态")
        }
        return findings
    }

    private fun error(field: String?, message: String) = Finding(FindingLevel.ERROR, field, message)
    private fun warn(field: String?, message: String) = Finding(FindingLevel.WARN, field, message)
}

fun List<Finding>.hasError(): Boolean = any { it.level == FindingLevel.ERROR }
