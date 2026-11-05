package com.cyj265.installments.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 写进系统日历的一条还款提醒。生成规则单独放在 [CalendarPlan] 里，好脱离 Android 做单测。 */
data class CalendarEntry(
    val marker: String,
    val title: String,
    val description: String,
    val startMillis: Long,
)

data class SyncOutcome(
    val written: Int = 0,
    val error: String? = null,
    /** 上次写进去、这次日历里找不到、而这期仍未还——多半是人在日历里手动删了。 */
    val vanished: List<String> = emptyList(),
    /** 检测到的日历清单，写不进去时人得靠它判断到底是被藏了还是只读。 */
    val calendars: String? = null,
)

object CalendarPlan {

    /** 只删带这个前缀的事件，绝不碰用户自己建的日程。 */
    const val SCHEME = "fq://"
    private const val HOUR = 9

    fun marker(loanId: String, index: Int): String = "${SCHEME}loan/$loanId/period/$index"

    /** 未还的每一期一条。已还的期不写，所以 App 里点「已还」= 日历里那条消失。 */
    fun entries(loans: List<Loan>, zone: ZoneId): List<CalendarEntry> = buildList {
        loans.filter { it.status == LoanStatus.ACTIVE }.forEach { loan ->
            loan.periods.filter { !it.paid }.forEach { period ->
                add(
                    CalendarEntry(
                        marker = marker(loan.id, period.index),
                        title = "分期还款 · ${loan.institution} ${Money.format(period.amountCents)}",
                        description = describe(loan, period),
                        startMillis = period.dueDate.atTime(HOUR, 0).atZone(zone).toInstant().toEpochMilli(),
                    ),
                )
            }
        }
    }.sortedBy { it.startMillis }

    /**
     * 反向信号只能做到这一步：Android 没有公开的待办/完成接口，日历里的「打勾」我们既读不到也写不了，
     * 所以拿「上次写过、这次没了、而这期还欠着」当作「可能已经还了」，交给人确认，不自动改账。
     */
    fun vanishedMarkers(lastWritten: Set<String>, existing: Set<String>, expected: Set<String>): List<String> =
        lastWritten.filter { it !in existing && it in expected }

    private fun describe(loan: Loan, period: Period): String = buildString {
        append("第 ${period.index}/${loan.totalPeriods} 期")
        val principal = period.principalCents
        val interest = period.interestCents
        when {
            principal != null && interest != null ->
                append(" · 本金 ${Money.format(principal)} + 利息 ${Money.format(interest)}")
            principal != null -> append(" · 本金 ${Money.format(principal)}")
        }
        append("\n由「分期管家」写入：在 App 里标记已还后，这条会在下次同步时删掉")
    }
}

/**
 * App → 本机日历的单向同步。事件由系统日历自己管闹钟，绕开后台被杀、定时漂移这两件事。
 *
 * 有些 ROM（实测 ColorOS 17）把 [CalendarContract.Calendars] 对第三方藏起来，查询回空表，
 * 但别的 App 照样能写——它们是自己建一个「本地日历」再往里塞。所以这里也是两条腿：
 * 先挨个试现有的日历，全都写不进去就自建一个只属于本 App 的日历。
 */
class CalendarSync(private val context: Context, private val settings: SettingsStore) {

    private val resolver: ContentResolver get() = context.contentResolver

    var enabled: Boolean
        get() = settings.calendarSync
        set(value) {
            settings.calendarSync = value
        }

    suspend fun sync(loans: List<Loan>): SyncOutcome = withContext(Dispatchers.IO) { syncBlocking(loans) }

    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        val removed = runCatching { deleteOurs() }.getOrDefault(0)
        settings.calendarWritten = emptySet()
        removed
    }

    private fun syncBlocking(loans: List<Loan>): SyncOutcome {
        if (!enabled) return SyncOutcome()
        if (!hasPermission()) {
            return SyncOutcome(error = "没有日历权限：去系统设置 → 应用 → 分期管家 → 权限里把「日历」打开")
        }
        val expected = CalendarPlan.entries(loans, ZoneId.systemDefault())
        val expectedMarkers = expected.map { it.marker }.toSet()
        // 先迁掉旧版挂在「假账户」下的日历，否则它们迟早会被系统按「账户不存在」整批清掉。
        val migrated = runCatching { removeLegacyCalendars() }.getOrDefault(0) > 0
        if (migrated) {
            settings.calendarOwnId = -1L
            settings.calendarWritten = emptySet()
        }
        val rows = readCalendars()
        val summary = summarize(rows)
        val existing = runCatching { existingMarkers() }.getOrElse {
            return SyncOutcome(error = "读不到日历事件：${it.describe()}", calendars = summary)
        }
        val vanished = if (migrated) {
            emptyList()
        } else {
            CalendarPlan.vanishedMarkers(settings.calendarWritten, existing, expectedMarkers)
        }

        // 整体重建：先删掉自己上次写的，再按「当前未还」写一遍。已还的期因此自然消失，
        // 重复写入、改金额、改日期也都跟着一次同步走完。
        runCatching { deleteOurs() }
        if (expected.isEmpty()) {
            settings.calendarWritten = emptySet()
            return SyncOutcome(0, null, vanished, summary)
        }

        // 探测只写第一条：能进去就说明这个日历可写，然后清干净再整体写，免得探一次留一堆重复。
        val probe = expected.first()
        var failure: String? = null
        var target: CalendarRow? = null
        var ownCalendar = false
        var created = false
        val remembered = settings.calendarOwnId
        if (remembered > 0) {
            val attempt = tryWrite(remembered, probe, preferSyncAdapter = true)
            runCatching { deleteOurs() }
            if (attempt.isSuccess) {
                target = CalendarRow(remembered, OWN_NAME, CalendarContract.Calendars.CAL_ACCESS_OWNER, primary = false)
                ownCalendar = true
            } else {
                settings.calendarOwnId = -1L
            }
        }
        if (target == null) {
            for (row in rows.sortedByDescending { it.accessLevel }) {
                val attempt = tryWrite(row.id, probe, preferSyncAdapter = false)
                runCatching { deleteOurs() }
                if (attempt.isSuccess) {
                    target = row
                    break
                }
                failure = attempt.exceptionOrNull()?.describe()
            }
        }
        if (target == null) {
            // 别的 App 能悄悄写日历，靠的就是自建一个本地日历。系统日历里会多出一个叫「分期管家」的日历，
            // 若默认被隐藏，人得在日历 App 的日历列表里把它勾上才看得见。
            val own = runCatching { ensureOwnCalendar() }
            val ownRow = own.getOrNull()
            if (ownRow != null) {
                settings.calendarOwnId = ownRow.id
                target = ownRow
                ownCalendar = true
                created = true
            } else {
                failure = own.exceptionOrNull()?.describe() ?: failure
            }
        }
        val chosen = target
            ?: return SyncOutcome(0, "写不进日历（$failure）。要不要我改成本地通知提醒？", vanished, summary)

        val written = mutableSetOf<String>()
        var writeError: String? = null
        expected.forEach { entry ->
            tryWrite(chosen.id, entry, preferSyncAdapter = ownCalendar)
                .onSuccess { written += entry.marker }
                .onFailure { writeError = it.describe() }
        }
        // 只记成功写进去的，失败的留给下次重试，否则下次会被误判成「被人删了」
        settings.calendarWritten = written
        val note = when {
            created -> "已自建「$OWN_NAME」日历并在其中写入 ${written.size} 期。系统日历里如果看不到，去日历 App 的日历列表里把「分期管家」勾上"
            writeError != null -> "写进「${chosen.displayName}」时出错：$writeError"
            else -> null
        }
        return SyncOutcome(written.size, note, vanished, summary)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun existingMarkers(): Set<String> {
        val out = mutableSetOf<String>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.CUSTOM_APP_URI),
            CUSTOM_SELECTION,
            OURS_ONLY,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { out += it }
        }
        return out
    }

    /** 先把事件写进指定的日历；普通入口被账户校验挡下，再试同步适配器入口。 */
    private fun tryWrite(calendarId: Long, entry: CalendarEntry, preferSyncAdapter: Boolean): Result<Unit> =
        runCatching { insertEvent(calendarId, entry, viaSyncAdapter = preferSyncAdapter) }
            .recoverCatching { insertEvent(calendarId, entry, viaSyncAdapter = !preferSyncAdapter) }

    private fun deleteOurs(): Int {
        val direct = runCatching {
            resolver.delete(CalendarContract.Events.CONTENT_URI, CUSTOM_SELECTION, OURS_ONLY)
        }.getOrDefault(0)
        if (direct > 0) return direct
        // 自建日历里的事件归我们的账户，普通入口删不掉，得再走一次同步适配器入口。
        val own = runCatching {
            resolver.delete(syncAdapterUri(CalendarContract.Events.CONTENT_URI), CUSTOM_SELECTION, OURS_ONLY)
        }.getOrDefault(0)
        if (own > 0) return own
        // 旧版假账户下的残留事件
        return runCatching {
            resolver.delete(legacyEventsUri(), CUSTOM_SELECTION, OURS_ONLY)
        }.getOrDefault(0)
    }

    private fun insertEvent(calendarId: Long, entry: CalendarEntry, viaSyncAdapter: Boolean) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, entry.title)
            put(CalendarContract.Events.DESCRIPTION, entry.description)
            put(CalendarContract.Events.DTSTART, entry.startMillis)
            put(CalendarContract.Events.DTEND, entry.startMillis + DURATION_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
            put(CalendarContract.Events.CUSTOM_APP_URI, entry.marker)
        }
        // 自建日历的账户归我们，写事件也得走同步适配器入口，否则 provider 会以「账户不匹配」拒绝。
        val eventsUri = if (viaSyncAdapter) syncAdapterUri(CalendarContract.Events.CONTENT_URI) else CalendarContract.Events.CONTENT_URI
        val uri = resolver.insert(eventsUri, values)
            ?: throw IllegalStateException("日历拒绝了这一条")
        resolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                put(CalendarContract.Reminders.MINUTES, 0)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
    }

    /**
     * 用「本地账户」建自己的日历。账户类型必须是系统认识的 [CalendarContract.ACCOUNT_TYPE_LOCAL]：
     * 早期版本拿包名当账户类型，CalendarProvider 监听账户变化时会把「AccountManager 里不存在」的账户
     * 连带其日历和事件整批清掉，表现就是过一阵子日历里的事自己没了。
     * 这条路径不需要真的实现 SyncAdapter，只是借它拿到「可写」的日历行。
     */
    private fun ensureOwnCalendar(): CalendarRow? {
        findOwnCalendar()?.let { return it }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, OWN_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, OWN_ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME, OWN_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, OWN_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, OWN_COLOR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, OWN_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = resolver.insert(syncAdapterUri(CalendarContract.Calendars.CONTENT_URI), values) ?: return null
        return CalendarRow(ContentUris.parseId(uri), OWN_NAME, CalendarContract.Calendars.CAL_ACCESS_OWNER, primary = false)
    }

    private fun findOwnCalendar(): CalendarRow? = runCatching {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            ),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.ACCOUNT_NAME} = ?",
            arrayOf(OWN_ACCOUNT_TYPE, OWN_NAME),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                CalendarRow(cursor.getLong(0), cursor.getString(1) ?: OWN_NAME, cursor.getInt(2), primary = false)
            } else {
                null
            }
        }
    }.getOrNull()

    /** 旧版按包名当账户类型建的那些日历，删干净（连带事件），免得日历里多出一份重复的。 */
    private fun removeLegacyCalendars(): Int {
        val ids = mutableListOf<Long>()
        runCatching {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(context.packageName),
                null,
            )?.use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
        }
        var removed = 0
        ids.forEach { id ->
            val args = arrayOf(id.toString())
            runCatching {
                resolver.delete(legacyEventsUri(), "${CalendarContract.Events.CALENDAR_ID} = ?", args)
            }
            runCatching {
                resolver.delete(legacyCalendarsUri(), "${CalendarContract.Calendars._ID} = ?", args)
            }.onSuccess { removed += it }
        }
        return removed
    }

    private fun legacyEventsUri(): Uri = syncAdapterUri(CalendarContract.Events.CONTENT_URI, accountType = context.packageName)

    private fun legacyCalendarsUri(): Uri = syncAdapterUri(CalendarContract.Calendars.CONTENT_URI, accountType = context.packageName)

    private fun syncAdapterUri(
        base: Uri,
        accountName: String = OWN_NAME,
        accountType: String = OWN_ACCOUNT_TYPE,
    ): Uri = base.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
        .build()

    /**
     * 不用列名查：部分 ROM 的 provider 缺 IS_PRIMARY 之类的列时会整条查询抛异常，
     * 而这个函数的结果要原样摊到界面上，越宽容越好。
     */
    private fun readCalendars(): List<CalendarRow> {
        val rows = mutableListOf<CalendarRow>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += CalendarRow(
                    id = cursor.getLongOr(cursor.getColumnIndex(CalendarContract.Calendars._ID)),
                    displayName = cursor.getStringOrNull(cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)) ?: "未命名日历",
                    accessLevel = cursor.getIntOr(cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)),
                    primary = cursor.getIntOr(cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)) == 1,
                )
            }
        }
        return rows
    }

    private fun android.database.Cursor.getLongOr(index: Int): Long = if (index >= 0) getLong(index) else 0L

    private fun android.database.Cursor.getIntOr(index: Int): Int = if (index >= 0) getInt(index) else 0

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0) getString(index) else null

    private fun summarize(rows: List<CalendarRow>): String =
        if (rows.isEmpty()) {
            "0 个（provider 对第三方返回空表）"
        } else {
            rows.joinToString("、") { row ->
                val level = when {
                    row.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR -> "可写"
                    row.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_READ -> "只读"
                    else -> "权限未知(${row.accessLevel})"
                }
                "${row.displayName}·$level"
            }
        }

    private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private data class CalendarRow(
        val id: Long,
        val displayName: String,
        val accessLevel: Int,
        val primary: Boolean,
    )

    private companion object {
        const val DURATION_MS = 30 * 60 * 1000L
        const val OWN_NAME = "分期管家"
        const val OWN_ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
        const val OWN_COLOR = 0xFF2F6B4F.toInt()
        val CUSTOM_SELECTION = "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?"
        val OURS_ONLY = arrayOf("${CalendarPlan.SCHEME}%")
    }
}
