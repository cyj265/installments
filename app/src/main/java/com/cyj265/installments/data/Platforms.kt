package com.cyj265.installments.data

/**
 * 平台字典：别名归一、有没有账单日、提示词里的对照表，全部从这一张表出。
 *
 * 这三件事以前分写在别名表、无账单日白名单和提示词规则 9 里，改一处漏两处。更麻烦的是
 * 别名表收过「抖音」「美团」这种短名，包含匹配会把「抖音放心借」「美团借钱」吞成月付——
 * 现金借款和信用付是两个产品，并成一条后合并是覆盖式的，错了没有任何提示。
 * 所以这里只收完整名，匹配一律最长优先。
 */
object Platforms {

    data class Spec(
        val canonical: String,
        val aliases: List<String> = emptyList(),
        /** 现金借款：钱直接到账、没有账单日这道手续，首期按放款日推。 */
        val cashLoan: Boolean = false,
    )

    val all: List<Spec> = listOf(
        Spec("支付宝花呗", listOf("花呗", "蚂蚁花呗", "信用购")),
        Spec("支付宝借呗", listOf("借呗", "蚂蚁借呗"), cashLoan = true),
        Spec("京东白条", listOf("白条")),
        Spec("京东金条", listOf("金条"), cashLoan = true),
        Spec("微众银行微粒贷", listOf("微粒贷", "微众"), cashLoan = true),
        Spec("网商银行网商贷", listOf("网商贷", "网商"), cashLoan = true),
        Spec("抖音月付"),
        Spec("抖音放心借", cashLoan = true),
        Spec("美团月付"),
        Spec("美团借钱", cashLoan = true),
        Spec("分期乐"),
        Spec("安逸花", listOf("马上消费金融"), cashLoan = true),
        Spec("360借条", listOf("奇富借条"), cashLoan = true),
        Spec("度小满有钱花", listOf("有钱花", "度小满"), cashLoan = true),
        Spec("招联金融", listOf("招联好期贷"), cashLoan = true),
        Spec("招商银行", listOf("招行", "招商")),
        Spec("浦发银行", listOf("浦发", "浦东发展")),
        Spec("交通银行", listOf("交行")),
        Spec("甘肃农商银行", listOf("甘肃农村商业银行")),
        Spec("建设银行", listOf("建行")),
        Spec("工商银行", listOf("工行")),
        Spec("中国银行", listOf("中行")),
        Spec("农业银行", listOf("农行")),
        Spec("邮储银行", listOf("邮储")),
        Spec("中信银行"),
        Spec("民生银行"),
        Spec("兴业银行"),
        Spec("光大银行"),
        Spec("平安银行"),
        Spec("广发银行"),
        Spec("华夏银行"),
    )

    private val names: List<Pair<String, Spec>> = all
        .flatMap { spec -> (listOf(spec.canonical) + spec.aliases).map { it to spec } }
        .sortedByDescending { (name, _) -> name.length }

    /** 表里没有的平台返回 null——由调用方决定是警告还是报错，不要在这里猜。 */
    fun spec(raw: String?): Spec? {
        val cleaned = raw?.filterNot { it.isWhitespace() }?.lowercase().orEmpty()
        if (cleaned.isEmpty()) return null
        return names.firstOrNull { (name, _) -> cleaned.contains(name.lowercase()) }?.second
    }

    /** 归一到标准名；表里没有的平台原样保留，人在确认页照样能改。 */
    fun normalize(raw: String?): String = spec(raw)?.canonical ?: raw?.trim().orEmpty()

    /** 拼给视觉模型看的那段对照表，和这里的表永远同源。 */
    fun promptTable(): String = buildString {
        append("现金借款（钱直接到账、没有账单日，statementDay 一律填 null）：")
        append(all.filter { it.cashLoan }.joinToString("、") { it.canonical })
        appendLine()
        append("账单型（先出账、后还款，页面上有账单日就照抄）：")
        append(all.filterNot { it.cashLoan }.joinToString("、") { it.canonical })
        appendLine()
        append("页面上的简称按这个写成标准名：")
        append(all.flatMap { spec -> spec.aliases.map { "$it→${spec.canonical}" } }.joinToString("，"))
    }
}
