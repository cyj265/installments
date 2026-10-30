package com.cyj265.installments.domain

import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.Platforms
import java.time.LocalDate

/**
 * 同一笔分期每个月都会产生一张新截图，能不能认出「是同一条」决定这个 App 是可用品还是垃圾堆。
 * 强键靠单号，弱键靠机构+每期金额+还款日+总期数，两者都命中不了时宁可让人裁决，也不静默新建。
 */
object LoanIdentity {

    /** 有没有账单日这道手续。UNKNOWN 表示表里没这个平台，交给校验器只提示、不拦人。 */
    enum class StatementKind { CASH_LOAN, BILLING, UNKNOWN }

    /** 机构名在不同截图里写法不稳定（花呗 / 支付宝花呗 / 蚂蚁花呗），不归一化就会判成两笔。 */
    fun normalizeInstitution(raw: String?): String = Platforms.normalize(raw)

    /**
     * 借呗、微粒贷这类现金借款钱直接到账，还款日跟着放款日走，没有出账这一步，
     * 硬要账单日只会把人卡在确认页；信用卡、花呗、白条先出账后还款，缺了账单日就推不准首期。
     * 平台表里没有的，按「不确定」处理：先按放款日算，人看到黄条再补账单日。
     */
    fun statementKind(institution: String?): StatementKind = when (Platforms.spec(institution)?.cashLoan) {
        true -> StatementKind.CASH_LOAN
        false -> StatementKind.BILLING
        null -> StatementKind.UNKNOWN
    }

    fun hasNoStatementDay(institution: String?): Boolean =
        statementKind(institution) == StatementKind.CASH_LOAN

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

    /**
     * 每期金额会变的账（等额本金、按日计息），下个月截图上那个数就不再是同一个，
     * 弱键必然落空。平台 + 放款日 + 借款金额这三样不随时间变，拿它认「还是那笔」。
     */
    fun stableKey(institution: String?, tradeDate: LocalDate?, principalTotalCents: Long?): String? {
        val inst = normalizeInstitution(institution)
        if (inst.isEmpty() || tradeDate == null || principalTotalCents == null || principalTotalCents <= 0L) return null
        return "T|$inst|$tradeDate|$principalTotalCents"
    }

    /** 「信用卡」「贷款」这种名字等于没写：下个月同一张卡的新截图会因此判成另一笔。 */
    fun isVagueInstitution(raw: String?): Boolean =
        raw?.filterNot { it.isWhitespace() }.orEmpty() in VAGUE_NAMES

    private val VAGUE_NAMES = setOf(
        "信用卡", "信用卡分期", "银行卡", "储蓄卡", "银行", "贷款", "借款", "分期", "分期付款", "消费贷", "网贷",
    )

    fun strongKey(loan: Loan): String? = strongKey(loan.institution, loan.loanNo)

    fun stableKey(loan: Loan): String? = stableKey(loan.institution, loan.tradeDate, loan.principalTotalCents)

    fun weakKey(loan: Loan): String? =
        weakKey(loan.institution, loan.perPeriodAmountCents, loan.dueDayOfMonth, loan.totalPeriods)
}

sealed interface DraftMatch {
    data object Unknown : DraftMatch
    data class Confirmed(val loan: Loan) : DraftMatch
    data class Suspect(val loan: Loan, val reason: String) : DraftMatch
}

fun matchLoan(
    existing: List<Loan>,
    institution: String?,
    loanNo: String?,
    perPeriodAmountCents: Long?,
    dueDayOfMonth: Int?,
    totalPeriods: Int?,
    tradeDate: LocalDate? = null,
    principalTotalCents: Long? = null,
): DraftMatch {
    val strong = LoanIdentity.strongKey(institution, loanNo)
    if (strong != null) {
        existing.firstOrNull { LoanIdentity.strongKey(it) == strong }?.let { return DraftMatch.Confirmed(it) }
    }
    // 弱键排在稳定键后面：等额本金那类每期金额逐月变，弱键下个月直接对不上，
    // 只有稳定键能把「同一笔新月份」认出来。它只提示、不自动合并，判错由人改。
    val stable = LoanIdentity.stableKey(institution, tradeDate, principalTotalCents)
    val stableHit = if (stable == null) null else existing.firstOrNull { LoanIdentity.stableKey(it) == stable }
    if (stableHit != null) {
        return DraftMatch.Suspect(stableHit, "平台、放款日、借款金额都相同，但单号对不上（或这次没抄到单号）")
    }
    val weak = LoanIdentity.weakKey(institution, perPeriodAmountCents, dueDayOfMonth, totalPeriods)
    if (weak != null) {
        existing.firstOrNull { candidate ->
            // 借呗这类没有单号、同平台多笔的账，只能靠「哪一天借了多少」区分。
            // 两边都读得到且不一致时必须否决；只有一边有值时不能否决——老记录没补放款日，
            // 否则同一个平台的下一张截图会被当成新债。
            LoanIdentity.weakKey(candidate) == weak &&
                (tradeDate == null || candidate.tradeDate == null || candidate.tradeDate == tradeDate) &&
                (principalTotalCents == null || candidate.principalTotalCents == null ||
                    candidate.principalTotalCents == principalTotalCents)
        }?.let {
            return DraftMatch.Suspect(
                it,
                "机构、每期金额、还款日、总期数都相同，放款日和借款金额也不冲突，但单号对不上",
            )
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
        // 借款本金总额只可能由人手工补过，模型永远不带这个字段，合并时别把它冲掉
        principalTotalCents = incoming.principalTotalCents ?: target.principalTotalCents,
        // 账单日/交易日期同理：填一次就该一直被用来算首期，后面的截图没拍到不代表账上没有
        statementDay = incoming.statementDay ?: target.statementDay,
        tradeDate = incoming.tradeDate ?: target.tradeDate,
        createdAt = target.createdAt,
        periods = periods,
    )
}
