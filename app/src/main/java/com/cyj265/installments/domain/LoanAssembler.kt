package com.cyj265.installments.domain

import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import com.cyj265.installments.data.Period
import java.time.Instant
import java.time.LocalDate
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
        val current = (draft.currentPeriod ?: 1).coerceAtLeast(1)
        val perPeriod = Money.parseCents(draft.perPeriodAmount) ?: 0L
        val nextDue = draft.nextDueDateParsed
        val dueDay = draft.dueDayOfMonth ?: nextDue?.dayOfMonth ?: 1
        val firstDue = if (nextDue != null) nextDue.minusMonths((current - 1).toLong()) else now.withDayOfMonth(dueDay.coerceIn(1, 28))

        val explicitPaid = draft.paidPeriods?.toSet()
        val periods = (1..total).map { i ->
            Period(
                index = i,
                dueDate = firstDue.plusMonths((i - 1).toLong()),
                amountCents = perPeriod,
                paid = explicitPaid?.contains(i) ?: (i < current),
            )
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
            principalOutstandingCents = Money.parseCents(draft.principalOutstanding),
            status = if (settled) LoanStatus.SETTLED else LoanStatus.ACTIVE,
            note = draft.note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = Instant.now(),
            periods = periods,
        )
    }

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

    /** 提前结清：剩余期全部置已还，本金口径清零。 */
    fun settleEarly(loan: Loan, on: LocalDate): Loan = loan.copy(
        periods = loan.periods.map { it.copy(paid = true, paidOn = it.paidOn ?: on) },
        principalOutstandingCents = 0L,
        status = LoanStatus.SETTLED,
        updatedAt = Instant.now(),
    )
}
