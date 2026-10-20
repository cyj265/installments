package com.cyj265.installments.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * 金额一律以「分」为单位存 Long，避免浮点误差在求和与对账时累积。
 * 只有和识别结果、用户输入打交道时才经过字符串。
 */
object Money {

    private val formatter = DecimalFormat(
        "#,##0.00",
        DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = ',' }
    )

    /** "¥1,234.50" -> 123450；无法解析返回 null。无小数位时按「元」理解。 */
    fun parseCents(raw: String?): Long? {
        val cleaned = raw?.filter { it.isDigit() || it == '.' || it == '-' } ?: return null
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
        return try {
            val value = cleaned.toBigDecimalOrNull() ?: return null
            if (value.signum() < 0) null else value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun format(cents: Long): String = formatter.format(BigDecimal(cents).movePointLeft(2))

    /** 界面展示用：带货币符号。 */
    fun formatWithSymbol(cents: Long): String = "¥" + format(cents)

    fun toBigDecimal(cents: Long): BigDecimal = BigDecimal(cents).movePointLeft(2)
}
