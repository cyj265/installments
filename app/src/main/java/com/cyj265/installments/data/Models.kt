package com.cyj265.installments.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object IsoDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IsoDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object IsoInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IsoInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

enum class LoanStatus { ACTIVE, SETTLED }

@Serializable
data class Period(
    val index: Int,
    @Serializable(with = IsoDateSerializer::class) val dueDate: LocalDate,
    val amountCents: Long,
    val paid: Boolean = false,
    @Serializable(with = IsoDateSerializer::class) val paidOn: LocalDate? = null,
    /**
     * 截图「还款计划」里这一期的本金与利息，照抄。等额本金每期利息不同，
     * 有这两列才能把未还本金、未付息费算准；没有就是 null，退回按比例摊。
     */
    val principalCents: Long? = null,
    val interestCents: Long? = null,
)

@Serializable
data class Loan(
    val id: String,
    val institution: String,
    val loanNo: String? = null,
    val totalPeriods: Int,
    /** 截图上标的「每期应还」，含手续费。尾期可能有尾差，所以不能由期数反推。 */
    val perPeriodAmountCents: Long,
    val dueDayOfMonth: Int,
    @Serializable(with = IsoDateSerializer::class) val firstDueDate: LocalDate,
    /** 信用卡的账单日（每月几号出账）。和 [dueDayOfMonth] 是两个日子，一起用来倒推首期还款日。 */
    val statementDay: Int? = null,
    /** 分期付款交易日期，推首期的起点。老记录没有这个字段，不能当必填。 */
    @Serializable(with = IsoDateSerializer::class) val tradeDate: LocalDate? = null,
    /** 截图上的「未还本金」，不含手续费。与 [remainingPayableCents] 是两个口径，不可互相替代。 */
    val principalOutstandingCents: Long? = null,
    /**
     * 借款本金总额（整笔合同本金）。截图一般不直接给这个数，由人在「编辑这笔」里补；
     * 一期没还过时 [principalOutstandingCents] 本身就等于它，[principalTotalOrNull] 会兜这一层。
     */
    val principalTotalCents: Long? = null,
    val status: LoanStatus = LoanStatus.ACTIVE,
    val note: String? = null,
    @Serializable(with = IsoDateSerializer::class) val createdAt: LocalDate,
    @Serializable(with = IsoInstantSerializer::class) val updatedAt: Instant,
    val periods: List<Period>,
) {
    val paidCount: Int get() = periods.count { it.paid }

    val unpaidPeriods: List<Period> get() = periods.filter { !it.paid }

    val nextUnpaid: Period? get() = unpaidPeriods.minByOrNull { it.index }

    val remainingPayableCents: Long get() = unpaidPeriods.sumOf { it.amountCents }

    /** 整笔的应还合计（全部期数，含息费）。已还过的期数也算进去，和 [remainingPayableCents] 是"全部 vs 剩余"。 */
    val planTotalCents: Long get() = periods.sumOf { it.amountCents }

    /** 每期金额是否不一样。借呗、微粒贷是等额本金，利息逐月减少，不能按固定月额摊。 */
    val amountsVary: Boolean get() = periods.map { it.amountCents }.distinct().size > 1

    /** 本期（或末期）应还多少。固定月额的记录它就是 [perPeriodAmountCents]。 */
    val currentAmountCents: Long
        get() = (nextUnpaid ?: periods.lastOrNull())?.amountCents ?: perPeriodAmountCents

    /** 截图逐期给出的本金合计；每期都抄到了本金才算数。 */
    val planPrincipalCents: Long?
        get() = periods.takeIf { it.isNotEmpty() && it.all { p -> p.principalCents != null } }
            ?.sumOf { it.principalCents!! }

    /**
     * 借款本金总额（确定值）。显式填过就用填的；逐期本金齐全时就是各期之和；
     * 一期都没还时未还本金就是全额本金，可以等价代用。
     * 还过款之后未还本金只剩剩余部分，不能再冒充总额，此时返回 null。
     */
    val principalTotalOrNull: Long?
        get() = principalTotalCents
            ?: planPrincipalCents
            ?: principalOutstandingCents?.takeIf { paidCount == 0 }

    /**
     * 待还本金：逐期本金抄到了就直接加；否则按整笔本金占应还合计的比例摊。
     *
     * 信用卡分期页给的「剩余未摊销金额」是**还没出账**的那部分本金，拿它当待还本金，
     * 剩余应还减它就会冒出一大块根本不存在的「未付息费」（0004 会算出 5,493.35）。
     * 知道整笔本金时优先按比例算；一期没还、又不知道总额时，未还本金本身就是待还本金。
     */
    val unpaidPrincipalCents: Long?
        get() {
            val remaining = remainingPayableCents
            if (remaining <= 0L) return 0L
            val unpaid = unpaidPeriods
            if (unpaid.all { it.principalCents != null }) return unpaid.sumOf { it.principalCents!! }
            val total = planTotalCents
            val principalTotal = principalTotalCents
            if (total > 0L && principalTotal != null) return (remaining * principalTotal + total / 2) / total
            return principalOutstandingCents?.takeIf { paidCount == 0 }
        }

    /** 待还部分里的息费：逐期利息抄到了就直接加，否则用剩余应还合计 − 待还本金。看不出本金时返回 null。 */
    val unpaidFeeCents: Long?
        get() {
            val unpaid = unpaidPeriods
            if (unpaid.isNotEmpty() && unpaid.all { it.interestCents != null }) return unpaid.sumOf { it.interestCents!! }
            return unpaidPrincipalCents?.let { (remainingPayableCents - it).coerceAtLeast(0L) }
        }

    /**
     * 按「每期本金均摊」把剩余本金推回整笔：每期本金 = 未还本金 ÷ 剩余期数。
     * 花呗、借呗、信用卡分期的本金都是均摊的，这个推算对它们是准的；等额本息的产品会差一点，
     * 所以界面上要标明是推算值，并允许在「编辑这笔」里填真实总额覆盖掉。
     */
    val principalTotalDerivedCents: Long?
        get() {
            val outstanding = principalOutstandingCents ?: return null
            val left = unpaidPeriods.size
            if (left <= 0 || totalPeriods <= 0) return null
            return (outstanding * totalPeriods + left / 2) / left
        }

    fun isOverdue(today: LocalDate): Boolean =
        status == LoanStatus.ACTIVE && nextUnpaid?.let { it.dueDate.isBefore(today) } == true

    fun overdueDays(today: LocalDate): Int =
        if (!isOverdue(today)) 0 else ChronoUnit.DAYS.between(nextUnpaid!!.dueDate, today).toInt()
}

/**
 * 还款计划里的一期，字段全是可空字符串：模型抄错不该让整条记录报废，
 * 能不能用交给 [InstallmentDraft.completePlan] 判断。
 */
@Serializable
data class DraftPeriod(
    val index: Int? = null,
    val dueDate: String? = null,
    val amount: String? = null,
    val principal: String? = null,
    val interest: String? = null,
) {
    val dueDateParsed: LocalDate?
        get() = dueDate?.let { raw -> runCatching { LocalDate.parse(raw.trim()) }.getOrNull() }
}

/**
 * 视觉模型输出的原始结构。字段刻意保持可空字符串：识别结果天生不可信，
 * 必须先原样摊到确认页，由用户改定后才转成强类型 Loan。
 */
@Serializable
data class InstallmentDraft(
    val institution: String,
    val loanNo: String? = null,
    val totalPeriods: Int? = null,
    val currentPeriod: Int? = null,
    /**
     * 截图上的「还剩几期 / 分 N 期还完」。借呗、多数银行信用贷的页面上压根没有「X/Y 期」，
     * 只有这个数，所以由 App 做 总−剩余+1 的减法，而不是让模型算月数差。
     */
    val remainingPeriods: Int? = null,
    val perPeriodAmount: String? = null,
    val principalOutstanding: String? = null,
    /**
     * 「商品金额 / 分期金额 / 交易金额」——整笔借款的本金，和 [principalOutstanding] 不是一回事：
     * 银行常把这两个数并排写在同一屏（16,000 与 5,333.33），抄混了总额和息费就全错。
     */
    val principalTotal: String? = null,
    val dueDayOfMonth: Int? = null,
    val nextDueDate: String? = null,
    /**
     * 账单日：每期在这天出账。信用分期页面上只写这个、不写还款日的很常见，
     * 缺它就推不出「交易后第一个账单日是几号」，首期日期只能瞎猜。
     */
    val statementDay: Int? = null,
    /** 分期付款交易日期（YYYY-MM-DD），推首期的起点。 */
    val tradeDate: String? = null,
    /**
     * 截图「还款计划」逐期照抄的明细。借呗、微粒贷多是等额本金，每期金额与利息都在变，
     * 只抄一个「每期金额」整条计划表就会失真，所以允许把逐期数字原样带进来。
     */
    val plan: List<DraftPeriod>? = null,
    val paidPeriods: List<Int>? = null,
    val note: String? = null,
) {
    val nextDueDateParsed: LocalDate?
        get() = nextDueDate?.let { raw -> runCatching { LocalDate.parse(raw.trim()) }.getOrNull() }

    val tradeDateParsed: LocalDate?
        get() = tradeDate?.let { raw -> runCatching { LocalDate.parse(raw.trim()) }.getOrNull() }

    /**
     * 能原样照抄的逐期计划：条数等于总期数，期号要么全没有（按顺序），要么正好是 1..总期数，
     * 每期日期和金额都读得出来。残缺的计划当没读到，退回固定月额，别拿半张表算账。
     */
    val completePlan: List<DraftPeriod>?
        get() {
            val list = plan?.takeIf { it.isNotEmpty() } ?: return null
            val total = totalPeriods ?: return null
            if (list.size != total) return null
            val indexed = list.all { it.index != null }
            val ordered = if (indexed) list.sortedBy { it.index } else list
            if (indexed && ordered.map { it.index } != (1..total).toList()) return null
            if (ordered.any { it.dueDateParsed == null || Money.parseCents(it.amount) == null }) return null
            return ordered
        }

    /**
     * 页面只有金额列、没有应还日列（交行分期详情就是这样）：期号与金额齐全，六期日期整列都空。
     * 日期按首期 + 每月推，和没有计划的记录同一套推法；金额、本金、利息仍按原文照抄。
     * 只缺其中一两期日期的算抄漏，不在这一类，照旧整张丢弃。
     */
    val planWithoutDates: List<DraftPeriod>?
        get() {
            val list = plan?.takeIf { it.isNotEmpty() } ?: return null
            val total = totalPeriods ?: return null
            if (list.size != total) return null
            val indexed = list.all { it.index != null }
            val ordered = if (indexed) list.sortedBy { it.index } else list
            if (indexed && ordered.map { it.index } != (1..total).toList()) return null
            if (ordered.any { it.dueDateParsed != null || Money.parseCents(it.amount) == null }) return null
            return ordered
        }

    /**
     * 人没直接改、但只给了「还剩几期」时推出来的当前期。装配和确认页共用这一条式子，
     * 否则页面显示「已还 0 期」而实际按第 5 期入库。
     */
    val currentPeriodOrDerived: Int?
        get() {
            currentPeriod?.let { return it }
            val n = totalPeriods ?: return null
            val left = remainingPeriods ?: return null
            return (n - left + 1).coerceAtLeast(1)
        }

    /**
     * 连续已还到第几期。paidPeriods 非空时它压过当前期，所以确认页得先把它算出来，
     * 才能告诉人「现在这一格实际会按几期已还入库」。
     */
    val paidThrough: Int
        get() {
            val explicit = paidPeriods?.toSet()?.takeIf { it.isNotEmpty() } ?: return ((currentPeriodOrDerived ?: 1) - 1).coerceAtLeast(0)
            var n = 0
            while (explicit.contains(n + 1)) n++
            return n
        }
}
