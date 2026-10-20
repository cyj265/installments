package com.cyj265.installments.domain

import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import java.time.LocalDate
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

    fun validateDraft(draft: InstallmentDraft): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (draft.institution.isBlank()) {
            findings += error("institution", "机构名为空，无法归档")
        }
        val total = draft.totalPeriods
        if (total == null) {
            findings += error("totalPeriods", "没读到总期数")
        } else if (total !in 1..360) {
            findings += error("totalPeriods", "总期数 $total 超出合理范围（1–360）")
        }
        if (Money.parseCents(draft.perPeriodAmount) == null) {
            findings += error("perPeriodAmount", "每期金额「${draft.perPeriodAmount ?: "空"}」无法解析成数字")
        }
        val current = draft.currentPeriod
        if (current != null && total != null && current > total) {
            findings += error("currentPeriod", "当前期 $current 大于总期数 $total")
        }
        val dueDate = draft.nextDueDateParsed
        val day = draft.dueDayOfMonth
        val rawDueDate = draft.nextDueDate?.trim()
        if (dueDate == null && !rawDueDate.isNullOrEmpty()) {
            findings += error("nextDueDate", "还款日「$rawDueDate」读不出日期，改成 YYYY-MM-DD，或清空它、只填「还款日」的每月几号")
        } else if (dueDate == null && day == null) {
            // 交行这类分期详情页压根不显示还款日，只有账单日固定这件事是确定的。
            // 只认完整日期会把人逼去猜一个 YYYY-MM-DD，而「每月 25 日」才是他真正知道的东西。
            findings += error("nextDueDate", "没读到还款日。截图上没有也没关系，在「还款日」里填每月几号即可，剩余期数会按月推算")
        }
        if (day != null && day !in 1..31) {
            findings += error("dueDayOfMonth", "还款日 $day 不在 1–31 之间")
        } else if (day != null && dueDate != null && day != dueDate.dayOfMonth) {
            findings += warn("dueDayOfMonth", "还款日填的是每月 $day 日，但识别到的日期是 $dueDate（${dueDate.dayOfMonth} 日）")
        }
        if (total != null && current != null && current == total && dueDate != null && dueDate.isBefore(LocalDate.now())) {
            findings += warn("currentPeriod", "末期且日期已过，确认这笔是否其实已经结清")
        }
        return findings
    }

    fun validateLoan(loan: Loan): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (loan.perPeriodAmountCents <= 0L) {
            findings += error("perPeriodAmount", "每期金额必须大于 0")
        }
        if (loan.dueDayOfMonth !in 1..31) {
            findings += error("dueDayOfMonth", "还款日 ${loan.dueDayOfMonth} 不在 1–31 之间")
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

        val sorted = loan.periods.sortedBy { it.index }
        for (i in 1 until sorted.size) {
            val months = ChronoUnit.MONTHS.between(sorted[i - 1].dueDate, sorted[i].dueDate)
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
                val gap = loan.remainingPayableCents - principal
                if (gap == loan.perPeriodAmountCents && gap > 0) {
                    findings += warn(
                        "principalOutstanding",
                        "未还本金比剩余应还合计少正好一期（${Money.format(gap)}），很可能把「剩余期数」读少了一期"
                    )
                }
                if (principal in 1 until loan.perPeriodAmountCents) {
                    findings += warn("principalOutstanding", "未还本金 ${Money.format(principal)} 小于单期金额，像是把尾期金额当成了本金")
                }
            }
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
