package com.cyj265.installments.domain

import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Period
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class DueItem(val loanId: String, val institution: String, val loanNo: String?, val period: Period)

data class PortfolioSummary(
    val activeCount: Int,
    val remainingPayableCents: Long,
    val principalCents: Long,
    val hasUnknownPrincipal: Boolean,
    val monthPayableCents: Long,
    val monthPayableCount: Int,
    val nextDue: DueItem?,
    val daysToNextDue: Long?,
    val overdueCount: Int,
    val paidPeriods: Int,
    val totalPeriods: Int,
    val finalSettleDate: LocalDate?,
)

/** 总览页和日历页的全部数字都从这里出，避免界面各算各的口径。 */
object Portfolio {

    fun activeLoans(loans: List<Loan>): List<Loan> = loans.filter { it.status == LoanStatus.ACTIVE }

    fun dues(loans: List<Loan>, from: LocalDate, to: LocalDate): Map<LocalDate, List<DueItem>> =
        activeLoans(loans)
            .flatMap { loan ->
                loan.unpaidPeriods.map { DueItem(loan.id, loan.institution, loan.loanNo, it) }
            }
            .filter { it.period.dueDate >= from && it.period.dueDate <= to }
            .groupBy { it.period.dueDate }
            .mapValues { (_, items) -> items.sortedBy { it.institution } }

    fun summarize(loans: List<Loan>, today: LocalDate): PortfolioSummary {
        val active = activeLoans(loans)
        val allUnpaid = active.flatMap { loan -> loan.unpaidPeriods.map { DueItem(loan.id, loan.institution, loan.loanNo, it) } }

        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val monthItems = allUnpaid.filter { it.period.dueDate >= monthStart && it.period.dueDate <= monthEnd }

        val next = allUnpaid.filter { it.period.dueDate >= today }.minByOrNull { it.period.dueDate }
        val principalLoans = active.filter { it.principalOutstandingCents != null }

        return PortfolioSummary(
            activeCount = active.size,
            remainingPayableCents = active.sumOf { it.remainingPayableCents },
            principalCents = principalLoans.sumOf { it.principalOutstandingCents ?: 0L },
            hasUnknownPrincipal = active.size != principalLoans.size,
            monthPayableCents = monthItems.sumOf { it.period.amountCents },
            monthPayableCount = monthItems.size,
            nextDue = next,
            daysToNextDue = next?.let { ChronoUnit.DAYS.between(today, it.period.dueDate) },
            overdueCount = active.count { it.isOverdue(today) },
            paidPeriods = active.sumOf { it.paidCount },
            totalPeriods = active.sumOf { it.totalPeriods },
            finalSettleDate = allUnpaid.maxOfOrNull { it.period.dueDate },
        )
    }
}
