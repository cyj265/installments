package com.cyj265.installments.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.cyj265.installments.data.DraftImport
import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.JsonLoanStore
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
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
) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState())
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
        }
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

    fun recognize(imageBytes: ByteArray) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, toast = null)
            runCatching { recognizer.recognize(imageBytes) }
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
                            toast = if (result.drafts.isEmpty()) "这张截图里没读到可用的分期信息，可以换一张更完整的再试" else "读到 ${result.drafts.size} 条，请逐条确认",
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

    fun delete(loanId: String) {
        val loan = find(loanId) ?: return
        viewModelScope.launch {
            store.remove(loanId)
            _state.value = _state.value.copy(
                loans = _state.value.loans.filterNot { it.id == loanId },
                toast = "已删除 ${loan.institution}",
            )
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
                )
            }
        }
    }
}
