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

    val recognizerReady: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api-inference.modelscope.cn/v1"
        const val DEFAULT_MODEL = "Qwen/Qwen2.5-VL-72B-Instruct"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_MODEL = "model"
    }
}

class RecognitionException(message: String) : Exception(message)

/**
 * OpenAI 兼容的视觉接口，够覆盖魔搭 API-Inference 与阿里云百炼。
 * 用 HttpURLConnection 而不是引 OkHttp：自用阶段只有一种请求形态，少一个依赖少一处版本风险。
 */
class VisionRecognizer(private val settings: SettingsStore) {

    suspend fun recognize(imageBytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (!settings.recognizerReady) {
            throw RecognitionException("还没填识别服务的 API Key，请改用「粘贴 JSON」导入")
        }
        val body = buildRequestBody(shrink(imageBytes))
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
        val root = runCatching { Json { isLenient = true }.parseToJsonElement(responseBody) }.getOrNull()
            ?: throw RecognitionException("识别服务的回答不是可读的 JSON（可能被限流或返回了 HTML）")
        val element = runCatching { root.jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"] }
            .getOrNull() ?: throw RecognitionException("识别服务没有返回内容：${responseBody.take(200)}")
        return element.jsonPrimitive.content
    }

    private fun buildRequestBody(jpeg: ByteArray): String {
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return buildJsonObject {
            put("model", settings.model)
            put("temperature", 0)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", PROMPT) })
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", dataUrl) }
                            })
                        }
                    },
                )
            }
        }.toString()
    }

    /** 截图普遍是 1080p 以上，模型侧的视觉 token 按边长分块计费，压到 1280 长边基本不丢字。 */
    private fun shrink(imageBytes: ByteArray): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return imageBytes
        try {
            val longest = maxOf(decoded.width, decoded.height)
            if (longest <= MAX_EDGE) return imageBytes
            val scale = MAX_EDGE.toFloat() / longest
            val resized = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            return java.io.ByteArrayOutputStream().use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, 82, out)
                if (resized != decoded) resized.recycle()
                out.toByteArray()
            }
        } finally {
            decoded.recycle()
        }
    }

    private companion object {
        const val MAX_EDGE = 1280
    }
}

private const val PROMPT = """你是分期还款台账录入员。只看这张截图上明确出现的内容，把它们抄成 JSON，不要推测、不要计算、不要补全截图上没有的数字。

只输出一个 JSON 对象，形如：
{"records":[{"institution":"支付宝花呗","loanNo":"****3271","totalPeriods":12,"currentPeriod":5,"perPeriodAmount":"680.00","principalOutstanding":"4760.00","dueDayOfMonth":28,"nextDueDate":"2026-10-28","paidPeriods":[1,2,3,4],"note":"截图原文里的备注"}]}

规则：
1. 金额一律照抄截图上的写法，用字符串，带小数点，不要带货币符号和千分位。
2. "未还本金/剩余本金"与"剩余应还/待还总额/本金+利息合计"是两个不同口径，截图上分别是多少就写多少；只有"未还本金"填 principalOutstanding，另一个填 note 里说明，不要混用。
3. currentPeriod 是"本期是第几期"，从 1 开始。totalPeriods 是总期数。
4. 日期用 YYYY-MM-DD。只写了"每月28日"这种，就填 dueDayOfMonth，nextDueDate 填 null。
5. 一张截图里若有多个独立分期单，全部放进 records。
6. 看不清或不存在的字段填 null，不要猜。单号被遮挡就照抄带星号的原文。"""
