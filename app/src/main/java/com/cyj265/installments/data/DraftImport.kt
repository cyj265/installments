package com.cyj265.installments.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 粘贴导入的解析入口。
 *
 * 用户复制的是 AI 回答，形态天生不稳定：可能是单个对象、数组、被 ``` 包裹、前后带解释文字，
 * 键名可能是英文也可能是中文，金额可能写成 `680` 也可能写成 `"¥680.00"`。
 * 所以这里做尽量宽松的取值，把"对不对"的判断完全交给 [LoanValidator] 和确认页，
 * 而不是在解析阶段就因为类型或键名不合预期而整条丢弃。
 */
object DraftImport {

    sealed interface Result {
        data class Ok(val drafts: List<InstallmentDraft>) : Result
        data class Failed(val message: String) : Result
    }

    private val json = Json { isLenient = true }

    private val envelopeKeys = listOf("records", "loans", "installments", "data", "items")

    private val aliases: Map<String, List<String>> = mapOf(
        "institution" to listOf("institution", "机构", "平台", "机构名称", "渠道"),
        "loanNo" to listOf("loanNo", "loan_no", "单号", "借款单号", "合同号", "订单号"),
        "totalPeriods" to listOf("totalPeriods", "total_periods", "总期数", "分期期数", "分期数", "期数"),
        "currentPeriod" to listOf("currentPeriod", "current_period", "当前期", "当前期数", "第几期", "已还期数"),
        "remainingPeriods" to listOf("remainingPeriods", "remaining_periods", "剩余期数", "未还期数", "还剩几期", "待还期数"),
        "perPeriodAmount" to listOf("perPeriodAmount", "per_period_amount", "每期金额", "每期应还", "月供", "单期金额"),
        "principalOutstanding" to listOf("principalOutstanding", "principal_outstanding", "未还本金", "剩余本金", "待还本金", "剩余未摊销金额"),
        "principalTotal" to listOf("principalTotal", "principal_total", "借款本金", "借款金额", "商品金额", "分期金额", "交易金额", "总本金"),
        "dueDayOfMonth" to listOf("dueDayOfMonth", "due_day", "due_day_of_month", "还款日", "还款日期号"),
        "nextDueDate" to listOf("nextDueDate", "next_due_date", "下期还款日", "下次还款日", "应还日期"),
        "statementDay" to listOf("statementDay", "statement_day", "账单日", "出账日", "账单日期"),
        "tradeDate" to listOf("tradeDate", "trade_date", "交易日期", "分期付款交易日期", "分期交易日期", "入账日期"),
        "plan" to listOf("plan", "periods", "schedule", "还款计划", "每期明细", "分期明细", "还款明细"),
        "paidPeriods" to listOf("paidPeriods", "paid_periods", "已还期", "已还期数列表"),
        "note" to listOf("note", "备注", "说明", "摘要"),
    )

    /** 还款计划里每一期的键名。和顶层字段分开：顶层「还款日」是每月几号，这里是一期一条的完整日期。 */
    private val periodAliases: Map<String, List<String>> = mapOf(
        "index" to listOf("index", "period", "periodIndex", "期数", "期号", "第几期", "序号"),
        "dueDate" to listOf("dueDate", "due_date", "date", "应还日", "应还日期", "到期日", "还款日"),
        "amount" to listOf("amount", "应还金额", "每期应还", "本期应还", "金额", "月供"),
        "principal" to listOf("principal", "本金", "应还本金"),
        "interest" to listOf("interest", "利息", "应还利息", "息费", "手续费"),
    )

    fun parse(text: String): Result {
        val payload = extractJson(text)
            ?: return Result.Failed("没找到 JSON。请确认粘贴的内容里有成对的 { } 或 [ ]")
        val element = runCatching { json.parseToJsonElement(payload) }
            .getOrElse { return Result.Failed("JSON 语法有误：${it.message ?: "结构不完整"}") }
        val objects = element.toRecordObjects()
        if (objects.isEmpty()) return Result.Failed("JSON 里没有分期记录对象")
        val drafts = objects.map { it.toDraft() }
        // 机构名空着也要放进来：银行分期页面上本来就不写平台归属，人只要在确认页补一格就能入库。
        // 只有期数、金额全空的对象才当垃圾丢掉，否则整批 JSON 会因为一个没认出来的机构被全数退回。
        val usable = drafts.filter { it.institution.isNotBlank() || it.hasEnoughToReview() }
        if (usable.isEmpty()) {
            return Result.Failed("读到了 ${drafts.size} 条记录，但既没有机构名，也没有期数和金额，无法归档")
        }
        return Result.Ok(usable)
    }

    private fun InstallmentDraft.hasEnoughToReview(): Boolean =
        completePlan != null ||
            listOf(totalPeriods != null, perPeriodAmount != null, principalOutstanding != null)
                .count { it } >= 2

    /** 依次尝试：``` 代码块内容、文本中第一段括号平衡的 JSON 片段。 */
    internal fun extractJson(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        fenced(trimmed)?.let { return it }
        return balancedSegment(trimmed)
    }

    private fun fenced(text: String): String? {
        val match = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```").find(text) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun balancedSegment(text: String): String? {
        val expected = ArrayList<Char>()
        var inString = false
        var escaped = false
        var start = -1
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> { if (expected.isEmpty()) start = i; expected += '}' }
                '[' -> { if (expected.isEmpty()) start = i; expected += ']' }
                '}', ']' -> {
                    if (expected.isEmpty()) return null
                    expected.removeAt(expected.size - 1)
                    if (expected.isEmpty()) return text.substring(start, i + 1)
                }
            }
        }
        // 括号没闭合就到底了：把残段交回 JSON 解析器，报错比"没找到"更贴近用户能修的问题
        return if (start >= 0) text.substring(start) else null
    }

    private fun JsonElement.toRecordObjects(): List<JsonObject> = when (this) {
        is JsonArray -> filterIsInstance<JsonObject>().ifEmpty { flatMap { it.toRecordObjects() } }
        is JsonObject -> {
            val nested = envelopeKeys.asSequence()
                .map { this[it] }
                .filterIsInstance<JsonArray>()
                .firstOrNull()
            nested?.filterIsInstance<JsonObject>() ?: listOf(this)
        }
        else -> emptyList()
    }

    private fun JsonObject.toDraft(): InstallmentDraft = InstallmentDraft(
        institution = string("institution").orEmpty(),
        loanNo = string("loanNo"),
        totalPeriods = int("totalPeriods"),
        currentPeriod = int("currentPeriod"),
        remainingPeriods = int("remainingPeriods"),
        perPeriodAmount = string("perPeriodAmount"),
        principalOutstanding = string("principalOutstanding"),
        principalTotal = string("principalTotal"),
        dueDayOfMonth = int("dueDayOfMonth") ?: dateOfDayFromNextDue(),
        nextDueDate = string("nextDueDate"),
        statementDay = dayOfMonth("statementDay"),
        tradeDate = string("tradeDate")?.let(::normalizeDate),
        plan = plan(),
        paidPeriods = intList("paidPeriods"),
        note = string("note"),
    )

    /** 返回第一个"存在且非 null"的别名键名；null 值要跳过，好让后面的中文别名继续尝试。 */
    private fun JsonObject.key(name: String, table: Map<String, List<String>> = aliases): String? =
        table[name]?.firstOrNull {
            val value = this[it]
            value != null && value !is JsonNull
        }

    private fun JsonObject.string(name: String, table: Map<String, List<String>> = aliases): String? {
        val alias = key(name, table) ?: return null
        val element = this[alias] as? JsonPrimitive ?: return null
        if (element is JsonNull) return null
        val raw = element.contentOrNull ?: return null
        val cleaned = raw.trim()
        return cleaned.takeIf { it.isNotEmpty() && it != "null" && it != "-" }
    }

    private fun JsonObject.int(name: String, table: Map<String, List<String>> = aliases): Int? {
        val raw = string(name, table) ?: return null
        val exact = raw.filter { it.isDigit() || it == '-' }.toIntOrNull()
        if (exact != null) return exact
        return Regex("\\d+").find(raw)?.value?.toIntOrNull()
    }

    private fun JsonObject.plan(): List<DraftPeriod>? {
        val alias = key("plan") ?: return null
        val array = this[alias] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonObject)?.toDraftPeriod() }.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.toDraftPeriod(): DraftPeriod? {
        val index = int("index", periodAliases)
        val due = string("dueDate", periodAliases)?.let(::normalizeDate)
        val amount = string("amount", periodAliases)
        if (index == null && due == null && amount == null) return null
        return DraftPeriod(
            index = index,
            dueDate = due,
            amount = amount,
            principal = string("principal", periodAliases),
            interest = string("interest", periodAliases),
        )
    }

    private fun JsonObject.intList(name: String): List<Int>? {
        val alias = key(name) ?: return null
        return when (val element = this[alias]) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitiveOrNull()?.let { raw -> Regex("\\d+").find(raw)?.value?.toIntOrNull() } }
            else -> string(name)?.split(",", "，", "、", " ")?.mapNotNull { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        }?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.dateOfDayFromNextDue(): Int? {
        val raw = string("nextDueDate") ?: return null
        return Regex("[/-](\\d{1,2})$").find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 日号优先认 1–31 的整数；遇到「2026-08-15」这种整条日期，取最后一段数字。 */
    private fun JsonObject.dayOfMonth(name: String): Int? {
        val raw = string(name) ?: return null
        raw.filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..31 }?.let { return it }
        return Regex("(\\d{1,2})(?!.*\\d)").find(raw)?.value?.toIntOrNull()?.takeIf { it in 1..31 }
    }

    /** 银行 App 上常写成 2026.08.25 或 2026年8月25日，统一成 ISO 再交给 LocalDate。 */
    private fun normalizeDate(raw: String): String? {
        val digits = Regex("(\\d{4})\\D+(\\d{1,2})\\D+(\\d{1,2})").find(raw) ?: return raw.trim()
        val (y, m, d) = digits.destructured
        return "%04d-%02d-%02d".format(y.toInt(), m.toInt(), d.toInt())
    }

    private fun JsonElement.jsonPrimitiveOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
}
