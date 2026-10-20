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
    /** 截图上的「未还本金」，不含手续费。与 [remainingPayableCents] 是两个口径，不可互相替代。 */
    val principalOutstandingCents: Long? = null,
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

    fun isOverdue(today: LocalDate): Boolean =
        status == LoanStatus.ACTIVE && nextUnpaid?.let { it.dueDate.isBefore(today) } == true

    fun overdueDays(today: LocalDate): Int =
        if (!isOverdue(today)) 0 else ChronoUnit.DAYS.between(nextUnpaid!!.dueDate, today).toInt()
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
    val perPeriodAmount: String? = null,
    val principalOutstanding: String? = null,
    val dueDayOfMonth: Int? = null,
    val nextDueDate: String? = null,
    val paidPeriods: List<Int>? = null,
    val note: String? = null,
) {
    val nextDueDateParsed: LocalDate?
        get() = nextDueDate?.let { raw -> runCatching { LocalDate.parse(raw.trim()) }.getOrNull() }
}
