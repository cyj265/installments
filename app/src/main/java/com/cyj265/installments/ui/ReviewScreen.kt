package com.cyj265.installments.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.Money
import com.cyj265.installments.domain.DraftMatch
import com.cyj265.installments.domain.FindingLevel
import com.cyj265.installments.domain.LoanAssembler
import com.cyj265.installments.domain.LoanValidator
import com.cyj265.installments.domain.matchLoan

/**
 * 屏 2：识别确认。入库前唯一的人工关口，所以把校验当主角：
 * 计算值不符、疑似重复都摊成可一键处置的提示条，而不是静默入库。
 */
@Composable
fun ReviewScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drafts = state.drafts
    if (drafts.isEmpty()) {
        EmptyState(
            title = "没有待确认的识别结果",
            body = "从总览页的「＋ 截图」或「粘贴 JSON」开始，识别出的内容会先落到这里，你确认后才会入库。",
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "共 ${drafts.size} 条待确认。逐条核对后入库，已存在的记录会按单号合并，不会重复计负债。",
                color = LocalFqColors.current.muted,
                style = TextStyle(fontSize = FqBodySmall),
            )
        }
        drafts.forEachIndexed { index, draft ->
            item(key = "draft-$index") {
                DraftCard(
                    index = index,
                    draft = draft,
                    state = state,
                    viewModel = viewModel,
                    onDiscard = {
                        viewModel.removeDraft(index)
                        if (viewModel.state.value.drafts.isEmpty()) onBack()
                    },
                    onBack = onBack,
                )
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(text = "返回", onClick = onBack, modifier = Modifier.weight(1f))
                TextButton(
                    text = "丢弃全部草稿",
                    onClick = { viewModel.clearDrafts(); onBack() },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun DraftCard(
    index: Int,
    draft: InstallmentDraft,
    state: AppUiState,
    viewModel: AppViewModel,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
) {
    val findings = remember(draft) { LoanValidator.validateDraft(draft) }
    val errorFields = findings.filter { it.level == FindingLevel.ERROR }.mapNotNull { it.field }.toSet()
    val assembled = remember(draft) { LoanAssembler.fromDraft(draft, now = state.today) }
    // 对账键一律取 assembled，和 commitDraft 用的是同一套，避免页面提示与实际入库结果不一致
    val match = remember(assembled, state.loans) {
        matchLoan(
            existing = state.loans,
            institution = assembled.institution,
            loanNo = assembled.loanNo,
            perPeriodAmountCents = assembled.perPeriodAmountCents,
            dueDayOfMonth = assembled.dueDayOfMonth,
            totalPeriods = assembled.totalPeriods,
        )
    }
    var principalDismissed by rememberSaveable("${index}-pd") { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "第 ${index + 1} 条",
                style = TextStyle(fontSize = FqBody, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            TextButton(text = "丢弃这条", onClick = onDiscard)
        }
        Spacer(Modifier.height(4.dp))

        FieldRow("机构", draft.institution, errorFields.contains("institution")) { v ->
            viewModel.updateDraft(index, draft.copy(institution = v))
        }
        FieldRow("借款单号", draft.loanNo ?: "", errorFields.contains("loanNo")) { v ->
            viewModel.updateDraft(index, draft.copy(loanNo = v.trim().takeIf { s -> s.isNotEmpty() }))
        }
        IntFieldRow("总期数", draft.totalPeriods, errorFields.contains("totalPeriods")) { v -> viewModel.updateDraft(index, draft.copy(totalPeriods = v)) }
        IntFieldRow("当前期", draft.currentPeriod, errorFields.contains("currentPeriod")) { v -> viewModel.updateDraft(index, draft.copy(currentPeriod = v)) }
        FieldRow("每期金额", draft.perPeriodAmount ?: "", errorFields.contains("perPeriodAmount")) { v ->
            viewModel.updateDraft(index, draft.copy(perPeriodAmount = v))
        }
        FieldRow("未还本金", draft.principalOutstanding ?: "", errorFields.contains("principalOutstanding")) { v ->
            viewModel.updateDraft(index, draft.copy(principalOutstanding = v))
        }
        IntFieldRow("还款日", draft.dueDayOfMonth, errorFields.contains("dueDayOfMonth"), placeholder = "每月几号") { v -> viewModel.updateDraft(index, draft.copy(dueDayOfMonth = v)) }
        FieldRow("下期还款日", draft.nextDueDate ?: "", errorFields.contains("nextDueDate"), placeholder = "可留空，如 2026-11-10") { v ->
            viewModel.updateDraft(index, draft.copy(nextDueDate = v.trim().takeIf { s -> s.isNotEmpty() }))
        }

        // 只填「每月几号」时，具体日期是推算出来的；不摊开给人看，就没法确认推的是不是那个月
        if (draft.nextDueDateParsed == null && draft.dueDayOfMonth != null && assembled.periods.isNotEmpty()) {
            AlertBox(
                text = "按每月 ${draft.dueDayOfMonth} 日推算：下期 ${assembled.nextUnpaid?.dueDate ?: "无"}，末期 ${assembled.periods.last().dueDate}。对不上就改「还款日」，或直接填完整日期。",
                kind = ChipKind.NEUTRAL,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // 实测：银行 App 的「借款详情」这一屏往往只有本金和总期数，每期金额与还款日在「查看还款计划」里。
        // 与其让人照着空框手填，不如直接说清该回去截哪一张。
        if (errorFields.contains("perPeriodAmount") && errorFields.contains("nextDueDate")) {
            AlertBox(
                text = "每期金额和下期还款日都不在这张截图上，它们通常在「查看还款计划」那一页。回去截那张再识别一次，比在这里手填快，也不会把期号填错。",
                kind = ChipKind.NEUTRAL,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        // 模型抄进 note 的那些字段（合同期限、已还本金…）是人判断这条对不对的关键上下文，不显示就等于丢了
        draft.note?.trim()?.takeIf { it.isNotEmpty() }?.let { extra ->
            AlertBox(text = "模型还从截图读到：$extra", kind = ChipKind.NEUTRAL, modifier = Modifier.padding(top = 10.dp))
        }

        // 口径不符提示：模型抄的"未还本金"和按剩余期数算出来的应还合计对不上。
        // 只在字段级 ERROR 已清掉之后再谈口径，否则期数本身就是残缺的，比出个错数只会误导。
        val principal = Money.parseCents(draft.principalOutstanding)
        val payable = assembled.remainingPayableCents
        if (!principalDismissed && errorFields.isEmpty() && principal != null && principal != payable) {
            val gap = payable - principal
            val onePeriod = assembled.perPeriodAmountCents
            AlertBox(
                text = if (gap == onePeriod && onePeriod > 0) {
                    "每期 ${Money.format(onePeriod)} × 剩余 ${assembled.unpaidPeriods.size} 期 = ${Money.format(payable)}，比抄到的未还本金多正好一期。很可能是把剩余期数读少了一期。"
                } else {
                    "抄到的未还本金 ${Money.format(principal)} 与剩余应还合计 ${Money.format(payable)} 不一致，差 ${Money.format(kotlin.math.abs(gap))}。本金不含手续费，两者本就该不同，但请确认哪个才是截图上的数。"
                },
                kind = if (gap < 0) ChipKind.LATE else ChipKind.NOW,
                modifier = Modifier.padding(top = 10.dp),
                actions = {
                    TextButton(
                        text = "采用 ${Money.format(payable)}",
                        onClick = { viewModel.updateDraft(index, draft.copy(principalOutstanding = Money.format(payable))) },
                    )
                    TextButton(text = "保留 ${Money.format(principal)}", onClick = { principalDismissed = true })
                    if (gap == onePeriod && onePeriod > 0) {
                        TextButton(
                            text = "当前期改 ${(draft.currentPeriod ?: 1) + 1}",
                            onClick = { viewModel.updateDraft(index, draft.copy(currentPeriod = (draft.currentPeriod ?: 1) + 1)) },
                        )
                    }
                },
            )
        }

        when (match) {
            is DraftMatch.Confirmed -> DupAlert(match.loan, "单号后 4 位与已有记录一致")
            is DraftMatch.Suspect -> DupAlert(match.loan, match.reason)
            DraftMatch.Unknown -> Unit
        }

        findings.filter { it.level == FindingLevel.WARN }.forEach { finding ->
            AlertBox(text = finding.message, kind = ChipKind.NOW, modifier = Modifier.padding(top = 10.dp))
        }
        findings.filter { it.level == FindingLevel.ERROR }.forEach { finding ->
            AlertBox(text = finding.message, kind = ChipKind.LATE, modifier = Modifier.padding(top = 10.dp))
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (match is DraftMatch.Suspect) {
                TextButton(text = "仍要新建", enabled = errorFields.isEmpty(), onClick = { finish(viewModel, index, forceNew = true, onBack) }, modifier = Modifier.weight(1f))
                TextButton(text = "合并更新", filled = true, enabled = errorFields.isEmpty(), onClick = { finish(viewModel, index, forceNew = false, onBack) }, modifier = Modifier.weight(2f))
            } else {
                TextButton(text = "取消", onClick = onDiscard, modifier = Modifier.weight(1f))
                TextButton(
                    text = if (match is DraftMatch.Confirmed) "确认合并入库" else "确认入库",
                    filled = true,
                    enabled = errorFields.isEmpty(),
                    onClick = { finish(viewModel, index, forceNew = false, onBack) },
                    modifier = Modifier.weight(2f),
                )
            }
        }
        DividerLine(modifier = Modifier.padding(top = 12.dp))
    }
}

private fun finish(viewModel: AppViewModel, index: Int, forceNew: Boolean, onEmpty: () -> Unit) {
    if (viewModel.commitDraft(index, forceNew) && viewModel.state.value.drafts.isEmpty()) onEmpty()
}

@Composable
private fun DupAlert(existing: Loan, reason: String) {
    AlertBox(
        text = "已存在 ${existing.institution} ${existing.loanNo ?: ""} · 第 ${existing.nextUnpaid?.index ?: existing.totalPeriods} / ${existing.totalPeriods} 期。$reason——这张截图大概率是同一笔的新月份。合并会把已还进度取并集、单号与本金口径以这次为准。",
        kind = ChipKind.NEUTRAL,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun FieldRow(label: String, value: String, warn: Boolean, placeholder: String = "未识别到", onValueChange: (String) -> Unit) {
    FqField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        warning = warn,
        placeholder = placeholder,
        trailing = "AI",
    )
}

@Composable
private fun IntFieldRow(label: String, value: Int?, warn: Boolean, placeholder: String = "未识别到", onValueChange: (Int?) -> Unit) {
    FieldRow(label, value?.toString() ?: "", warn, placeholder) { raw ->
        onValueChange(raw.filter { it.isDigit() }.toIntOrNull())
    }
}
