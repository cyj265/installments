package com.cyj265.installments.domain

import com.cyj265.installments.data.Loan

/**
 * 同一笔分期每个月都会产生一张新截图，能不能认出「是同一条」决定这个 App 是可用品还是垃圾堆。
 * 强键靠单号，弱键靠机构+每期金额+还款日+总期数，两者都命中不了时宁可让人裁决，也不静默新建。
 */
object LoanIdentity {

    /** 机构名在不同截图里写法不稳定（花呗 / 支付宝花呗 / 蚂蚁花呗），不归一化就会判成两笔。 */
    private val aliases = mapOf(
        "花呗" to "支付宝花呗",
        "蚂蚁花呗" to "支付宝花呗",
        "信用购" to "支付宝花呗",
        "借呗" to "支付宝借呗",
        "蚂蚁借呗" to "支付宝借呗",
        "白条" to "京东白条",
        "京东" to "京东白条",
        "金条" to "京东金条",
        "微粒贷" to "微众银行微粒贷",
        "微众" to "微众银行微粒贷",
        "招行" to "招商银行",
        "招商银行" to "招商银行",
        "月付" to "抖音月付",
        "抖音" to "抖音月付",
        "美团" to "美团月付",
    )

    fun normalizeInstitution(raw: String?): String {
        val cleaned = raw?.filterNot { it.isWhitespace() }?.lowercase() ?: return ""
        if (cleaned.isEmpty()) return ""
        aliases[cleaned]?.let { return it }
        val hit = aliases.entries.firstOrNull { (alias, _) -> cleaned.contains(alias.lowercase()) }
        return hit?.value ?: raw!!.trim()
    }

    /**
     * 同一个单号在截图里可能是遮挡的 `****3271`，也可能是完整的 `202603271`。
     * 两边都只取后 4 位比对，两种形态才会落到同一个键；有效字符不足 4 位时退回弱键。
     */
    fun strongKey(institution: String?, loanNo: String?): String? {
        val tail = loanNo?.filter { it.isLetterOrDigit() }?.lowercase().orEmpty()
        if (tail.length < 4) return null
        val inst = normalizeInstitution(institution)
        if (inst.isEmpty()) return null
        return "S|$inst|${tail.takeLast(4)}"
    }

    fun weakKey(
        institution: String?,
        perPeriodAmountCents: Long?,
        dueDayOfMonth: Int?,
        totalPeriods: Int?,
    ): String? {
        val inst = normalizeInstitution(institution)
        if (inst.isEmpty() || perPeriodAmountCents == null || dueDayOfMonth == null || totalPeriods == null) {
            return null
        }
        return "W|$inst|$perPeriodAmountCents|$dueDayOfMonth|$totalPeriods"
    }

    fun strongKey(loan: Loan): String? = strongKey(loan.institution, loan.loanNo)

    fun weakKey(loan: Loan): String? =
        weakKey(loan.institution, loan.perPeriodAmountCents, loan.dueDayOfMonth, loan.totalPeriods)
}

sealed interface DraftMatch {
    data object Unknown : DraftMatch
    data class Confirmed(val loan: Loan) : DraftMatch
    data class Suspect(val loan: Loan, val reason: String) : DraftMatch
}

fun matchLoan(existing: List<Loan>, institution: String?, loanNo: String?, perPeriodAmountCents: Long?, dueDayOfMonth: Int?, totalPeriods: Int?): DraftMatch {
    val strong = LoanIdentity.strongKey(institution, loanNo)
    if (strong != null) {
        existing.firstOrNull { LoanIdentity.strongKey(it) == strong }?.let { return DraftMatch.Confirmed(it) }
    }
    val weak = LoanIdentity.weakKey(institution, perPeriodAmountCents, dueDayOfMonth, totalPeriods)
    if (weak != null) {
        existing.firstOrNull { LoanIdentity.weakKey(it) == weak }?.let {
            return DraftMatch.Suspect(it, "机构、每期金额、还款日、总期数都相同，但单号对不上")
        }
    }
    return DraftMatch.Unknown
}

/** 把用户裁决后的「合并」落到具体字段上：单号与本金口径以新截图为准，已还进度保留历史。 */
fun mergeLoan(target: Loan, incoming: Loan): Loan {
    val paidUnion = target.periods.mapNotNull { if (it.paid) it.index else null }.toSet() +
        incoming.periods.mapNotNull { if (it.paid) it.index else null }.toSet()
    val periods = incoming.periods.map { it.copy(paid = it.paid || it.index in paidUnion) }
    return incoming.copy(
        id = target.id,
        institution = target.institution.ifBlank { incoming.institution },
        loanNo = incoming.loanNo ?: target.loanNo,
        createdAt = target.createdAt,
        periods = periods,
    )
}
