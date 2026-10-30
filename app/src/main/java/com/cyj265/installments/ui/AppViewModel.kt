package com.cyj265.installments.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.cyj265.installments.data.CalendarSync
import com.cyj265.installments.data.DraftImport
import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.JsonLoanStore
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import com.cyj265.installments.data.SettingsStore
import com.cyj265.installments.data.StoreFile
import com.cyj265.installments.data.VisionRecognizer
import com.cyj265.installments.domain.DraftMatch
import com.cyj265.installments.domain.FindingLevel
import com.cyj265.installments.domain.LoanAssembler
import com.cyj265.installments.domain.LoanValidator
import com.cyj265.installments.domain.matchLoan
import com.cyj265.installments.domain.mergeLoan
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class AppUiState(
    val loans: List<Loan> = emptyList(),
    val drafts: List<InstallmentDraft> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val toast: String? = null,
    val calendarEnabled: Boolean = false,
    val calendarCount: Int = 0,
    /** 检测到的日历清单，写不进去时人得靠它判断是没日历还是只读。 */
    val calendarSummary: String? = null,
    val calendarProblem: String? = null,
)

/**
 * 界面唯一的数据入口。所有写入都走同一条顺序：
 * 校验 → 组装 → 对账（强键命中就合并，弱键命中只提示、由人裁决）→ 落盘 → 重算总览。
 * 这样"手改一笔"和"导入十笔"不会走上两套判定。
 */
class AppViewModel(
    private val store: JsonLoanStore,
    val settings: SettingsStore,
    private val recognizer: VisionRecognizer,
    private val calendar: CalendarSync,
) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState(calendarEnabled = settings.calendarSync))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val loaded = runCatching { store.load() }
            _state.value = _state.value.copy(
                loans = loaded.getOrDefault(emptyList()),
                loading = false,
                toast = loaded.exceptionOrNull()?.let { "读取本地数据失败：${it.message}" },
            )
            // 启动时也同步一次：人在日历里手动删掉的事件只有这时候才发现得了
            syncCalendar(reportError = false)
        }
    }

    /** 打开/关闭日历同步。打开时由界面先要到日历权限再调这里。 */
    fun setCalendarSync(on: Boolean) {
        viewModelScope.launch {
            calendar.enabled = on
            _state.value = _state.value.copy(calendarEnabled = on)
            if (on) {
                val outcome = calendar.sync(_state.value.loans)
                _state.value = _state.value.copy(
                    calendarCount = outcome.written,
                    calendarSummary = outcome.calendars,
                    calendarProblem = outcome.error,
                    toast = outcome.error ?: "已把 ${outcome.written} 期未还还款日写进本机日历，还款日早上 9 点提醒",
                )
            } else {
                val removed = calendar.clear()
                _state.value = _state.value.copy(
                    calendarCount = 0,
                    calendarProblem = null,
                    toast = "已从日历删除 $removed 条还款提醒",
                )
            }
        }
    }

    /**
     * 台账一变就把日历重建一遍：已还的期自动从日历消失，改过的金额/日期也跟着更新。
     * [reportError] 为 false 时只报「事件被删了」，不报权限/日历缺失——否则每次冷启动都弹一次同样的错；
     * 但问题本身要留在设置页上，否则人点开关看不出为什么没写进去。
     */
    private suspend fun syncCalendar(reportError: Boolean) {
        if (!calendar.enabled) return
        val outcome = calendar.sync(_state.value.loans)
        val note = when {
            outcome.error != null -> if (reportError) outcome.error else null
            outcome.vanished.isNotEmpty() ->
                "日历里有 ${outcome.vanished.size} 条还款事件被删掉了。如果是已经还过的期，去对应分期里点一下「已还」"
            else -> null
        }
        _state.value = _state.value.copy(
            calendarCount = outcome.written,
            calendarSummary = outcome.calendars,
            calendarProblem = outcome.error,
            toast = note ?: _state.value.toast,
        )
    }

    fun updateDraft(index: Int, draft: InstallmentDraft) {
        val list = _state.value.drafts.toMutableList()
        if (index in list.indices) list[index] = draft
        _state.value = _state.value.copy(drafts = list)
    }

    fun removeDraft(index: Int) {
        val list = _state.value.drafts.toMutableList()
        if (index in list.indices) list.removeAt(index)
        _state.value = _state.value.copy(drafts = list)
    }

    fun clearDrafts() {
        _state.value = _state.value.copy(drafts = emptyList())
    }

    fun pasteJson(text: String) {
        when (val result = DraftImport.parse(text)) {
            is DraftImport.Result.Failed -> _state.value = _state.value.copy(toast = result.message)
            is DraftImport.Result.Ok -> _state.value = _state.value.copy(
                drafts = _state.value.drafts + result.drafts,
                toast = "读到 ${result.drafts.size} 条，请逐条确认",
            )
        }
    }

    fun recognize(images: List<ByteArray>) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, toast = null)
            runCatching { recognizer.recognize(images) }
                .onSuccess { raw ->
                    when (val result = DraftImport.parse(raw)) {
                        is DraftImport.Result.Failed -> _state.value = _state.value.copy(
                            busy = false,
                            toast = "模型返回的内容读不懂：${result.message}",
                        )
                        is DraftImport.Result.Ok -> _state.value = _state.value.copy(
                            busy = false,
                            drafts = _state.value.drafts + result.drafts,
                            // 空结果必须吭一声，否则点了「＋ 截图」像是没生效
                            toast = if (result.drafts.isEmpty()) "没读到可用的分期信息。「还款计划」那种按月列几行应还的是多笔借款合计，读不出单笔，请改选逐笔列出的「应还款详情」" else "读到 ${result.drafts.size} 条，请逐条确认",
                        )
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(busy = false, toast = it.message ?: "识别失败")
                }
        }
    }

    /**
     * 确认页入库一条草稿。返回 false 表示还有 ERROR 没解决，界面应留在确认页。
     * [forceNew] 是用户在"疑似重复"横幅上选「仍要新建」，此时跳过合并。
     */
    fun commitDraft(index: Int, forceNew: Boolean = false): Boolean {
        val draft = _state.value.drafts.getOrNull(index) ?: return false
        val findings = LoanValidator.validateDraft(draft)
        findings.firstOrNull { it.level == FindingLevel.ERROR }?.let {
            _state.value = _state.value.copy(toast = it.message)
            return false
        }
        val assembled = LoanAssembler.fromDraft(draft, now = _state.value.today)
        val match = matchLoan(
            existing = _state.value.loans,
            institution = assembled.institution,
            loanNo = assembled.loanNo,
            perPeriodAmountCents = assembled.perPeriodAmountCents,
            dueDayOfMonth = assembled.dueDayOfMonth,
            totalPeriods = assembled.totalPeriods,
            tradeDate = assembled.tradeDate,
            principalTotalCents = assembled.principalTotalOrNull,
        )
        val final = if (forceNew) {
            assembled
        } else {
            when (match) {
                is DraftMatch.Confirmed -> mergeLoan(match.loan, assembled)
                is DraftMatch.Suspect -> mergeLoan(match.loan, assembled)
                DraftMatch.Unknown -> assembled
            }
        }
        LoanValidator.validateLoan(final).firstOrNull { it.level == FindingLevel.ERROR }?.let {
            _state.value = _state.value.copy(toast = it.message)
            return false
        }
        _state.value = _state.value.copy(drafts = _state.value.drafts.filterIndexed { i, _ -> i != index })
        persist(final, if (forceNew) "已新建 ${final.institution}" else "已保存 ${final.institution}")
        return true
    }

    fun togglePaid(loanId: String, periodIndex: Int, paid: Boolean) {
        val loan = find(loanId) ?: return
        persist(
            LoanAssembler.togglePaid(loan, periodIndex, paid, _state.value.today),
            if (paid) "第 $periodIndex 期已标记还清" else "第 $periodIndex 期改回未还",
        )
    }

    fun settleEarly(loanId: String) {
        val loan = find(loanId) ?: return
        persist(LoanAssembler.settleEarly(loan, _state.value.today), "${loan.institution} 已提前结清")
    }

    fun reopen(loanId: String) {
        val loan = find(loanId) ?: return
        persist(
            loan.copy(
                status = LoanStatus.ACTIVE,
                periods = loan.periods.map { it.copy(paid = false, paidOn = null) },
                updatedAt = Instant.now(),
            ),
            "${loan.institution} 已恢复为还款中",
        )
    }

    fun editNote(loanId: String, note: String?) {
        val loan = find(loanId) ?: return
        persist(
            loan.copy(note = note?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = Instant.now()),
            "备注已更新",
        )
    }

    /**
     * 明细页订正一笔。返回 false 表示有字段填的不是数字、或改完和剩余应还自相矛盾，
     * 界面应停在编辑态让人接着改，别把一次写坏的金额静默存进台账。
     */
    fun editLoan(
        loanId: String,
        institution: String,
        loanNo: String?,
        perPeriodAmount: String?,
        principalOutstanding: String?,
        principalTotal: String?,
        dueDay: String?,
        statementDay: String?,
        tradeDate: String?,
    ): Boolean {
        val loan = find(loanId) ?: return false
        if (institution.isBlank()) {
            _state.value = _state.value.copy(toast = "机构名不能为空，强键靠它和单号后 4 位认这笔")
            return false
        }
        val perPeriod = Money.parseCents(perPeriodAmount)
        if (perPeriod == null || perPeriod <= 0L) {
            _state.value = _state.value.copy(toast = "每期金额「${perPeriodAmount?.trim() ?: "空"}」不是数字")
            return false
        }
        val principalText = principalOutstanding?.trim().orEmpty()
        val principal = when {
            principalText.isEmpty() -> null
            else -> Money.parseCents(principalText) ?: run {
                _state.value = _state.value.copy(toast = "未还本金「$principalText」不是数字，不知道就留空")
                return false
            }
        }
        val totalText = principalTotal?.trim().orEmpty()
        val total = when {
            totalText.isEmpty() -> null
            else -> Money.parseCents(totalText) ?: run {
                _state.value = _state.value.copy(toast = "借款本金「$totalText」不是数字，不知道就留空")
                return false
            }
        }
        val day = dueDay?.trim()?.toIntOrNull()
        if (day == null || day !in 1..31) {
            _state.value = _state.value.copy(toast = "还款日要填 1–31 的日号，比如每月 15 日就填 15")
            return false
        }
        val statementText = statementDay?.trim().orEmpty()
        val statement = when {
            statementText.isEmpty() -> null
            else -> statementText.toIntOrNull()?.takeIf { it in 1..31 } ?: run {
                _state.value = _state.value.copy(toast = "账单日要填 1–31 的日号，不知道就留空")
                return false
            }
        }
        val tradeText = tradeDate?.trim().orEmpty()
        val trade = when {
            tradeText.isEmpty() -> null
            else -> runCatching { LocalDate.parse(tradeText) }.getOrNull() ?: run {
                _state.value = _state.value.copy(toast = "交易日期「$tradeText」读不出，改成 2026-08-25 这种格式，或留空")
                return false
            }
        }
        val planTotal = perPeriod * loan.totalPeriods
        if (total != null) {
            if (principal != null && total < principal) {
                _state.value = _state.value.copy(toast = "借款本金不能小于未还本金（${Money.format(principal)}）")
                return false
            }
            if (total > planTotal) {
                _state.value = _state.value.copy(toast = "借款本金比全部 ${loan.totalPeriods} 期应还 ${Money.format(planTotal)} 还多，两者至少有一个填错了")
                return false
            }
        }
        val edited = LoanAssembler.applyEdits(
            loan, institution, loanNo, perPeriod, principal, total,
            dueDayOfMonth = day, statementDay = statement, tradeDate = trade,
        )
        LoanValidator.validateLoan(edited).firstOrNull { it.level == FindingLevel.ERROR }?.let {
            _state.value = _state.value.copy(toast = it.message)
            return false
        }
        persist(edited, "已更新 ${edited.institution}")
        return true
    }

    fun delete(loanId: String) {
        val loan = find(loanId) ?: return
        viewModelScope.launch {
            store.remove(loanId)
            _state.value = _state.value.copy(
                loans = _state.value.loans.filterNot { it.id == loanId },
                toast = "已删除 ${loan.institution}",
            )
            syncCalendar(reportError = true)
        }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }

    /** 界面层（选图、读写备份文件）的失败也走同一条提示通道，避免两套 toast。 */
    fun showMessage(text: String) {
        _state.value = _state.value.copy(toast = text)
    }

    private val exportJson = Json { prettyPrint = true; encodeDefaults = true }
    private val importJson = Json { ignoreUnknownKeys = true }

    fun exportText(): String =
        exportJson.encodeToString(
            StoreFile.serializer(),
            StoreFile(savedAt = Instant.now().toString(), loans = _state.value.loans),
        )

    fun restore(text: String) {
        runCatching { importJson.decodeFromString<StoreFile>(text) }
            .onSuccess { file ->
                // 字段全有默认值，所以随便一个 JSON 也能 decode 成功。恢复是唯一能把台账清零的入口，
                // 拿笔数当门槛：宁可拒绝一个真的空备份，也不能让选错文件毁掉现有记录。
                if (file.loans.isEmpty()) {
                    _state.value = _state.value.copy(toast = "这个文件里没有分期记录，已取消恢复，当前 ${_state.value.loans.size} 笔没动")
                } else {
                    viewModelScope.launch {
                        store.save(file.loans)
                        _state.value = _state.value.copy(loans = file.loans, toast = "已从备份恢复 ${file.loans.size} 笔")
                        syncCalendar(reportError = true)
                    }
                }
            }
            .onFailure { _state.value = _state.value.copy(toast = "备份文件读不出来：${it.message}") }
    }

    fun dataFilePath(): String = store.filePath

    private fun find(id: String): Loan? = _state.value.loans.firstOrNull { it.id == id }

    private fun persist(loan: Loan, message: String) {
        viewModelScope.launch {
            store.put(loan)
            val current = _state.value.loans
            val i = current.indexOfFirst { it.id == loan.id }
            _state.value = _state.value.copy(
                loans = if (i < 0) current + loan else current.toMutableList().apply { this[i] = loan },
                toast = message,
            )
            syncCalendar(reportError = true)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val settings = SettingsStore(app)
                AppViewModel(
                    store = JsonLoanStore(File(app.filesDir, "loans.json")),
                    settings = settings,
                    recognizer = VisionRecognizer(settings),
                    calendar = CalendarSync(app, settings),
                )
            }
        }
    }
}
