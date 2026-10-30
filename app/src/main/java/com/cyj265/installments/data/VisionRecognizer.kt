package com.cyj265.installments.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 识别服务的配置。Key 只落在应用私有目录的 SharedPreferences 里：
 * 不进 Git、不写外部存储、不随导出备份走。一旦要对外发布，这条路必须改成走自己的服务端。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("fq_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.trim()).apply()
        }

    /** 「写入本机日历」开关。 */
    var calendarSync: Boolean
        get() = prefs.getBoolean(KEY_CALENDAR_SYNC, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CALENDAR_SYNC, value).apply()
        }

    /** 上一条同步实际写进日历的事件标记，用来发现「被人从日历里删掉了」。 */
    var calendarWritten: Set<String>
        get() = prefs.getStringSet(KEY_CALENDAR_WRITTEN, emptySet())?.toSet().orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_CALENDAR_WRITTEN, value.toSet()).apply()
        }

    /** 自建的「分期管家」日历 id。ROM 对第三方藏日历清单时，只能靠记住这个 id 找回自己的日历。 */
    var calendarOwnId: Long
        get() = prefs.getLong(KEY_CALENDAR_OWN_ID, -1L)
        set(value) {
            prefs.edit().putLong(KEY_CALENDAR_OWN_ID, value).apply()
        }

    val recognizerReady: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api-inference.modelscope.cn/v1"
        const val DEFAULT_MODEL = "Qwen/Qwen2.5-VL-72B-Instruct"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_MODEL = "model"
        private const val KEY_CALENDAR_SYNC = "calendarSync"
        private const val KEY_CALENDAR_WRITTEN = "calendarWritten"
        private const val KEY_CALENDAR_OWN_ID = "calendarOwnId"
    }
}

class RecognitionException(message: String) : Exception(message)

/**
 * OpenAI 兼容的视觉接口，够覆盖魔搭 API-Inference 与阿里云百炼。
 * 用 HttpURLConnection 而不是引 OkHttp：自用阶段只有一种请求形态，少一个依赖少一处版本风险。
 */
class VisionRecognizer(private val settings: SettingsStore) {

    private val lenientJson = Json { isLenient = true }

    /**
     * 一次可以发多张：同一笔分期的「借款详情」和「还款计划」各缺一半，
     * 分两轮识别就只能靠人手工补，放同一轮里模型自己能拼成一条。
     */
    suspend fun recognize(images: List<ByteArray>): String = withContext(Dispatchers.IO) {
        if (!settings.recognizerReady) {
            throw RecognitionException("还没填识别服务的 API Key，请改用「粘贴 JSON」导入")
        }
        if (images.isEmpty()) throw RecognitionException("没有收到图片")
        val body = buildRequestBody(images.map { shrink(it) })
        val conn = URL(settings.baseUrl + "/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream).use { it.readBytes() }.toString(Charsets.UTF_8)
            if (code !in 200..299) {
                throw RecognitionException("识别服务返回 $code：${text.take(200)}")
            }
            extractContent(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractContent(responseBody: String): String {
        val root = runCatching { lenientJson.parseToJsonElement(responseBody) }.getOrNull()
            ?: throw RecognitionException("识别服务的回答不是可读的 JSON（可能被限流或返回了 HTML）")
        val element = runCatching { root.jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"] }
            .getOrNull() ?: throw RecognitionException("识别服务没有返回内容：${responseBody.take(200)}")
        return element.jsonPrimitive.content
    }

    private fun buildRequestBody(jpegs: List<ByteArray>): String {
        return buildJsonObject {
            put("model", settings.model)
            put("temperature", 0)
            // 多图意味着可能一次吐出好几条记录，不显式给足长度的话，端点默认值会把 JSON 截断在半路；
            // 逐期计划一条就是十几行，12 期 + 本金/利息轻松过 2K，得按 4K 给
            put("max_tokens", 4096)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", PROMPT) })
                            jpegs.forEach { jpeg ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))
                                    }
                                })
                            }
                        }
                    },
                )
            }
        }.toString()
    }

    /**
     * 普通截图压到长边 1280：模型侧的视觉 token 按边长分块计费，这个尺寸基本不丢字。
     * 长截图（逐期计划那种，宽高比大于 2）不能等比这么压——1080×4000 等比缩到长边 1280，
     * 宽度只剩 346px，期数和金额会糊成一片，抄出来的计划表就全错了。
     */
    private fun shrink(imageBytes: ByteArray): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return imageBytes
        try {
            val scale = scaleFor(decoded.width, decoded.height)
            if (scale >= 1f) return imageBytes
            val resized = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            // 长图整屏都是数字，质量给低了会把 8 和 6 压成一个
            val quality = if (isTall(decoded.width, decoded.height)) TALL_JPEG_QUALITY else JPEG_QUALITY
            return java.io.ByteArrayOutputStream().use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (resized != decoded) resized.recycle()
                out.toByteArray()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun isTall(width: Int, height: Int): Boolean =
        maxOf(width, height).toFloat() / minOf(width, height) > TALL_ASPECT

    /** 长图按「短边不低于 900，长边不超过 4096」缩放；普通图仍按长边 1280。 */
    private fun scaleFor(width: Int, height: Int): Float {
        val longEdge = maxOf(width, height).toFloat()
        val shortEdge = minOf(width, height).toFloat()
        if (!isTall(width, height)) return if (longEdge <= MAX_EDGE) 1f else MAX_EDGE / longEdge
        val byShort = minOf(1f, TALL_SHORT_EDGE / shortEdge)
        return minOf(byShort, TALL_MAX_LONG_EDGE / longEdge)
    }

    companion object {
        const val MAX_EDGE = 1280
        private const val JPEG_QUALITY = 82
        private const val TALL_JPEG_QUALITY = 86
        /** 宽高比超过这个数就算长截图（逐期计划整屏那种）。 */
        private const val TALL_ASPECT = 2f
        /** 长截图缩放后短边至少留这么宽，再窄就认不出表格里的数字。 */
        private const val TALL_SHORT_EDGE = 900f
        private const val TALL_MAX_LONG_EDGE = 4096f

        /**
         * 真正发给模型的那段话。公开它是因为「我的」页要原样显示并允许复制——
         * 没有 Key、手动去问模型的人拿的是同一段指令，两处一旦分叉，粘回来的 JSON 就对不上校验规则。
         * 平台对照表由 [Platforms] 生成，和别名归一、账单日判定同源，不会各写一份。
         */
        val PROMPT: String = BASE_PROMPT + "\n\n平台对照表：\n" + Platforms.promptTable()

        private const val BASE_PROMPT = """你是分期还款台账录入员。只看这些截图上明确出现的内容，把它们抄成 JSON，不要推测、不要计算、不要补全截图上没有的数字。只有规则 11、12 列出的推算例外。

只输出一个 JSON 对象，形如：
{"records":[{"institution":"支付宝花呗","loanNo":"****3271","totalPeriods":12,"currentPeriod":5,"remainingPeriods":8,"perPeriodAmount":"680.00","principalTotal":"8160.00","principalOutstanding":"4760.00","tradeDate":"2026-08-25","statementDay":15,"dueDayOfMonth":28,"nextDueDate":"2026-10-28","paidPeriods":[1,2,3,4],"note":"截图原文里的备注"}]}

规则：
1. 金额一律照抄截图上的写法，用字符串，带小数点，不要带货币符号和千分位。
2. institution 填这笔债挂在哪个平台，一律用文末「平台对照表」左列的标准名；表里没有的平台就按页面上写的名字原样抄。页面上写着"放款机构""资金来源"的银行只是出资方，不是归属，把它抄进 note——同一个借呗的钱可能来自四五家银行，按银行填会把一个平台的账拆散。不许写"信用卡""银行卡""贷款""分期"这类泛指：银行信用卡要填发卡行（页面上的银行名或卡面前缀，如"招商银行"），实在看不出是哪家就填 null，让人在确认页补。
3. loanNo 只抄明确标着"借款单号/合同号/借据编号"的那串。"网商银行(7417)""尾号1430"这类是收款账户或银行卡尾号，不是单号，填 null。
4. "未还本金/剩余本金/使用中金额"与"剩余应还/待还总额/待入账金额/本金+利息合计"是两个不同口径，截图上分别是多少就写多少；只有前者填 principalOutstanding，另一个在 note 里说明，不要混用。像"待入账金额¥1,902.71（本金¥1,842.05 + 利息¥60.66）"这种，principalOutstanding 填 1842.05。
5. 只有"X/Y期"这种写法才给得出期数：Y 是 totalPeriods，X 是 currentPeriod。"共4期""剩余4期""分4期还完""该笔额度将分4期还完"说的是还剩几期没还，填 remainingPeriods，不是 totalPeriods；"剩余期数/总期数 6期/12期"这种，totalPeriods 填 12、remainingPeriods 填 6，currentPeriod 填 null，减法交给 App 做。
6. paidPeriods 只写明确标着"已还/已还清/已扣款/还款成功"的期号。"已入账""待入账""待还""已摊销""已摊还""剩余期数"都是账单口径（账已出或本金已入账），钱不一定还了，一律不要写进 paidPeriods；截图上只有这类字样时 paidPeriods 填 null。
7. 一张截图按月份列出好几行应还、金额逐月变小的（如"11月09日应还31,306.31 / 12月09日应还14,293.59 / 01月09日应还11,641.94"），是好几笔借款合并到一张账单上的汇总，不是一笔分期的各期。不要为它编一条记录，records 留空，让人去截逐笔列出的"应还款详情"。
8. 一次可能给你多张截图：机构与单号都相同的多张才属于同一笔，合并成一条，某个字段哪张上出现就取哪张。单号缺失时不许只凭机构就合并——借款金额或总期数不同的，一律各成一条。同一字段在不同张上数值不一样时，取更完整那张的值，并在 note 里写明冲突。
9. 日期用 YYYY-MM-DD。三个日期字段各有出处，不许互相顶替：
 - 「分期付款交易日期 / 交易日期 / 借款日 / 放款日」→ tradeDate，整条日期照抄（如 2026.08.25 写成 "2026-08-25"）。
 - 「账单日 15 日 / 每月 15 日出账」→ statementDay，只填日号 15；账单日不是还款日，不要拿它填 dueDayOfMonth。
 - 「还款日 / 到期还款日 / 每月9日自动还」→ dueDayOfMonth，只填日号；nextDueDate 填 null。
 - 这两个日号常常只写在页面标题或说明文字里（"每月15日出账""下期账单日：15日""每月21日应还"），没放进表格也得抄：带"出账/账单"的进 statementDay，带"还款/扣款/应还/到期"的进 dueDayOfMonth。这两格空着，首期月份就推错或干脆推不出来。
 平台对照表里标为「现金借款」的没有账单日，钱直接到账：这些页面上不要编 statementDay，填 null；页面上有「借款日/放款日」就抄进 tradeDate。
 表里没有的平台，页面上没写账单日就填 null，不要自己补一个。
 页面上只有账单日、没有还款日时，dueDayOfMonth 填 null，并在 note 里写"账单日X日，未写还款日"。
10. 看不清或不存在的字段填 null，不要猜。单号被遮挡就照抄带星号的原文。规则 11、12 列出的推算除外。
11. 只允许下面三种推算。用了哪一条，都必须把截图上支撑它的原文一并写进 note，让人在确认页上能核对；这三条之外一律照抄，不许自己算。
 a) 页面上同时出现借款或合同的起始日与到期日两个日期 → totalPeriods 填这两个日期相差的整月数，note 里原样写出这两个日期。
 b) 截图上是逐条列出的已还还款记录（每条有实际还款日期和金额）、且没有标期号 → 按不同的还款日期数出已还了几期，同一个日期拆成好几笔的只算一期（比如一笔本金一笔利息），currentPeriod 填这个数加 1，paidPeriods 填 null，note 里把这些日期原样列出来。这是已还记录，不是规则 7 说的按月应还汇总。
 c) 已还记录里所有还款日期都落在同一个日（例如全在 9 日）→ dueDayOfMonth 填那个日，nextDueDate 填 null。
12. 截图上把每期还款拆成「本金 / 利息 / 费用」几行列出来时，perPeriodAmount 要写每期实还额 = 月还款额 + 每期费用，两者相加是允许的唯一算法。
 例：月还款额 ¥5,333.34、上月已摊销费用 ¥80.00、利率 0.00% → perPeriodAmount 填 "5413.34"，note 里原样写"月还款额5333.34 + 每期费用80"。
 同屏若有「已摊销金额 ÷ 已摊销期数」，用商核对：10,826.67 ÷ 2 = 5,413.335，对得上才写；对不上就把两个数都写进 note，perPeriodAmount 只抄月还款额。
 同一屏上的「商品金额/分期金额/交易金额」是整笔借款本金，填 principalTotal；「剩余未摊销金额/剩余本金」才是 principalOutstanding；「已摊销金额」含费用，不是本金，只抄进 note。两个本金字段不许互相顶替。
13. 截图上有逐期列出的「还款计划/还款明细/每期明细」（一期一行，带期号、应还金额，通常还带应还日）时，照抄成 plan 数组：
 "plan":[{"index":1,"dueDate":"2026-10-09","amount":"5413.34","principal":"5333.33","interest":"80.01"}]
 - dueDate 抄这一期完整的应还日期，不是「每月9日」这种日号；日号仍按规则 9 写进 dueDayOfMonth。
 - amount 是这一期实还额（本金+利息+费用），principal、interest 抄该期对应两列，截图上没有就填 null。
 - 期数要和 totalPeriods 一致，index 从 1 连续排到 totalPeriods。截图只露出部分期数、或只有某几期缺应还日/金额时，plan 整个填 null，不要补、不要编；但整张表都没有「应还日」这一列（只有每期金额、本金、利息）不算残缺：照抄期号、金额、本金、利息，dueDate 一律填 null，别因为没日期就整张不抄。
 - 逐期明细一律进 plan，不许塞进 note。note 只写截图上的额外说明（利率、贴息、每期费用、已摊销）。哪怕页面上是把每期拆成一句文字（"第1期 7,548.86 = 本金 6,901.71 + 利息 647.15，其中财政预贴息 35.69"），也要一行一期拆进 plan——只写进 note，确认页就没有逐期日期和金额，等于没抄。
 - 现金借款（借呗、微粒贷这类，见平台对照表）有等额本金（每期金额递减）也有等额本息（每期金额相同），两种都必须用 plan 逐期照抄，不要只给一个 perPeriodAmount，也不要自己按月摊。不要替它判断还款方式：截图上没写"等额本金/等额本息"就别在 note 里写。用了 plan 时 perPeriodAmount 填首期金额即可，App 以 plan 为准。"""
    }
}
