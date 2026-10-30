package com.cyj265.installments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import com.cyj265.installments.data.Period
import com.cyj265.installments.domain.FindingLevel
import com.cyj265.installments.domain.LoanValidator
import java.time.LocalDate

/** 屏 4：分期详情。刻度块可点选，点一格就能改那一期的已还状态。 */
@Composable
fun DetailScreen(
    loan: Loan?,
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (loan == null) {
        EmptyState(title = "这条记录已经不在了", body = "返回总览重新选择。")
        return
    }
    val fq = LocalFqColors.current
    var selected by rememberSaveable(loan.id) { mutableStateOf(loan.nextUnpaid?.index ?: loan.totalPeriods) }
    var confirmDelete by rememberSaveable(loan.id) { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf(loan.note ?: "") }
    var editingFields by remember { mutableStateOf(false) }
    var instDraft by remember { mutableStateOf(loan.institution) }
    var noDraft by remember { mutableStateOf(loan.loanNo ?: "") }
    var amountDraft by remember { mutableStateOf(Money.format(loan.perPeriodAmountCents)) }
    var principalDraft by remember { mutableStateOf(loan.principalOutstandingCents?.let { Money.format(it) } ?: "") }
    var totalDraft by remember { mutableStateOf(loan.principalTotalCents?.let { Money.format(it) } ?: "") }
    var dueDayDraft by remember { mutableStateOf(loan.dueDayOfMonth.toString()) }
    var statementDraft by remember { mutableStateOf(loan.statementDay?.toString() ?: "") }
    var tradeDraft by remember { mutableStateOf(loan.tradeDate?.toString() ?: "") }
    val chosen = loan.periods.firstOrNull { it.index == selected }
    val findings = remember(loan) { LoanValidator.validateLoan(loan) }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(6.dp))
            Text(text = "剩余应还合计（含息费）", color = fq.muted, style = TextStyle(fontSize = FqLabel))
            Text(
                text = Money.formatWithSymbol(loan.remainingPayableCents),
                style = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = buildString {
                    // 借呗的借款单号有 38 位，原样摊出来会把「每月几号 · 每期多少」挤到第二行。
                    // 对账强键只取后 4 位，所以这里显尾号，完整号在「编辑这笔」里能看能改。
                    val no = loan.loanNo
                    append(if (no == null) "没有单号" else if (no.length > 12) "尾号 ${no.takeLast(4)}" else no)
                    append(" · 每月 ${loan.dueDayOfMonth} 日")
                    loan.statementDay?.let { append("（账单日 $it 日）") }
                    // 等额本金（借呗）每期金额不同，写一个固定数会给人错的安全感
                    append(if (loan.amountsVary) " · 本期 ${Money.formatWithSymbol(loan.currentAmountCents)}（逐期不同）"
                    else " · 每期 ${Money.formatWithSymbol(loan.perPeriodAmountCents)}")
                    if (loan.status == LoanStatus.ACTIVE) append(" × 剩余 ${loan.unpaidPeriods.size} 期")
                },
                color = fq.muted,
                style = TextStyle(fontSize = FqLabel),
            )
            Spacer(Modifier.height(10.dp))
            TickBlocks(loan = loan, selected = selected, onSelect = { selected = it }, modifier = Modifier.fillMaxWidth())
        }

        item {
            Spacer(Modifier.height(12.dp))
            ReadoutCard(loan, chosen, viewModel, state.today)
        }

        item {
            TotalCard(loan)
        }

        // 一期没还时「总计」和「剩余」是同一组数，再摆一遍只是让人多读一遍。
        if (loan.paidCount > 0) {
            item {
                RemainingCard(loan)
            }
        }

        findings.filter { it.level != FindingLevel.ERROR }.forEach { finding ->
            item { AlertBox(text = finding.message, kind = ChipKind.NOW, modifier = Modifier.padding(top = 10.dp)) }
        }
        findings.filter { it.level == FindingLevel.ERROR }.forEach { finding ->
            item { AlertBox(text = finding.message, kind = ChipKind.LATE, modifier = Modifier.padding(top = 10.dp)) }
        }

        item {
            SectionTitle("计划表", "${loan.paidCount} / ${loan.totalPeriods} 期已还")
        }
        loan.periods.forEach { period ->
            item(key = period.index) {
                PlanRow(period, loan, state.today) { selected = period.index }
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            SectionTitle("本笔信息", if (editingFields) null else "识别错了就在这里改")
            if (editingFields) {
                FqField(label = "机构", value = instDraft, onValueChange = { instDraft = it })
                FqField(label = "借款单号", value = noDraft, onValueChange = { noDraft = it })
                FqField(
                    label = if (loan.amountsVary) "每期金额（改了就摊平）" else "每期金额",
                    value = amountDraft,
                    onValueChange = { amountDraft = it },
                )
                FqField(label = "未还本金", value = principalDraft, placeholder = "没有就留空", onValueChange = { principalDraft = it })
                FqField(label = "借款本金（总额）", value = totalDraft, placeholder = "不知道就留空", onValueChange = { totalDraft = it })
                FqField(label = "交易日期", value = tradeDraft, placeholder = "如 2026-08-25", onValueChange = { tradeDraft = it })
                FqField(label = "还款日", value = dueDayDraft, placeholder = "每月几号，如 15", onValueChange = { dueDayDraft = it })
                FqField(label = "账单日", value = statementDraft, placeholder = "信用卡分期必填，如 15；借呗类留空", onValueChange = { statementDraft = it })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "保存",
                        filled = true,
                        onClick = {
                            if (viewModel.editLoan(
                                    loan.id, instDraft, noDraft, amountDraft, principalDraft, totalDraft,
                                    dueDayDraft, statementDraft, tradeDraft,
                                )
                            ) {
                                editingFields = false
                            }
                        },
                    )
                    TextButton(
                        text = "取消",
                        onClick = {
                            instDraft = loan.institution
                            noDraft = loan.loanNo ?: ""
                            amountDraft = Money.format(loan.perPeriodAmountCents)
                            principalDraft = loan.principalOutstandingCents?.let { Money.format(it) } ?: ""
                            totalDraft = loan.principalTotalCents?.let { Money.format(it) } ?: ""
                            dueDayDraft = loan.dueDayOfMonth.toString()
                            statementDraft = loan.statementDay?.toString() ?: ""
                            tradeDraft = loan.tradeDate?.toString() ?: ""
                            editingFields = false
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "改账单日或还款日会按「交易 → 第一个账单日出账 → 还款日扣款」重算首期，整条计划表跟着重排，" +
                        "已还标记仍留在各自的期号上。借呗、微粒贷这类没有账单日，只按放款日和还款日推首期" +
                        "（支用日离还款日不足 15 天顺延一个月）；没有交易日期时只挪日号、不动月份。" +
                        "逐期金额不同的记录，只有真的改了「每期金额」才会摊平成同一个数。总期数不在这里改：" +
                        "它会打乱期号，而错了没有任何数字能自证，需要改就删掉这笔重新导入。",
                    color = fq.muted,
                    style = TextStyle(fontSize = FqLabel),
                )
            } else {
                TextButton(
                    text = "编辑这笔",
                    onClick = {
                        instDraft = loan.institution
                        noDraft = loan.loanNo ?: ""
                        amountDraft = Money.format(loan.perPeriodAmountCents)
                        principalDraft = loan.principalOutstandingCents?.let { Money.format(it) } ?: ""
                        totalDraft = loan.principalTotalCents?.let { Money.format(it) } ?: ""
                        dueDayDraft = loan.dueDayOfMonth.toString()
                        statementDraft = loan.statementDay?.toString() ?: ""
                        tradeDraft = loan.tradeDate?.toString() ?: ""
                        editingFields = true
                    },
                )
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            SectionTitle("备注", if (loan.note.isNullOrBlank()) "未填" else "已填")
            if (editingNote) {
                FqField(label = "备注", value = noteDraft, onValueChange = { noteDraft = it })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "保存备注",
                        filled = true,
                        onClick = {
                            viewModel.editNote(loan.id, noteDraft)
                            editingNote = false
                        },
                    )
                    TextButton(
                        text = "取消",
                        onClick = {
                            noteDraft = loan.note ?: ""
                            editingNote = false
                        },
                    )
                }
            } else {
                TextButton(text = "编辑备注", onClick = { noteDraft = loan.note ?: ""; editingNote = true })
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = if (loan.status == LoanStatus.SETTLED) "恢复为还款中" else "提前结清",
                    onClick = {
                        if (loan.status == LoanStatus.SETTLED) viewModel.reopen(loan.id) else viewModel.settleEarly(loan.id)
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (confirmDelete) "再点一次确认删除" else "删除这笔",
                    onClick = {
                        if (confirmDelete) {
                            viewModel.delete(loan.id)
                            onBack()
                        } else {
                            confirmDelete = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

/**
 * 整笔的总计：已还 + 未还，全部期数的应还合计、借款本金、总息费。
 *
 * 截图上只给「未还本金」这个剩余口径。一期没还过时它恰好等于借款本金，可以直接代用；
 * 还过款后先按「每期本金均摊」推回整笔并标成推算值，人填了真实借款金额就以填的为准。
 */
@Composable
private fun TotalCard(loan: Loan) {
    val fq = LocalFqColors.current
    val planTotal = loan.planTotalCents
    val exact = loan.principalTotalOrNull
    // 卡分期的「未还本金」是没出账那一期的本金，不是待还部分的本金，按它乘回整笔会少一大半；
    // 这种记录只能等人在「编辑这笔」里填真实借款金额。
    val derived = if (exact == null && loan.statementDay == null) loan.principalTotalDerivedCents else null
    val principalTotal = exact ?: derived
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fq.surface2)
            .border(1.dp, fq.line, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "总计 · 全部 ${loan.totalPeriods} 期", color = fq.muted, style = TextStyle(fontSize = FqBodySmall))
            Text(text = "${loan.paidCount} 期已还", color = fq.muted, style = TextStyle(fontSize = FqBodySmall))
        }
        Spacer(Modifier.height(7.dp))
        KeyValue("应还合计（含息费）", Money.formatWithSymbol(planTotal))
        Spacer(Modifier.height(5.dp))
        KeyValue(
            key = "借款本金",
            value = principalTotal?.let { Money.formatWithSymbol(it) + if (derived != null) "（推算）" else "" } ?: "—",
        )
        Spacer(Modifier.height(5.dp))
        KeyValue(
            key = "总计息费",
            value = principalTotal?.let { Money.formatWithSymbol((planTotal - it).coerceAtLeast(0L)) } ?: "—",
        )
        val hint = when {
            derived != null -> "借款本金由「未还本金 ÷ 剩余期数 × 总期数」按本金均摊推算（花呗、借呗、信用卡分期都这么摊）。" +
                "跟合同对不上时，在下面「编辑这笔」里填实际借款金额。"
            principalTotal == null -> "截图上没有整笔借款金额，在下面「编辑这笔」里填了才能算总计息费。"
            else -> null
        }
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = hint, color = fq.muted, style = TextStyle(fontSize = FqLabel))
        }
    }
}

/**
 * 剩余口径。两个数相差的就是还没付出去的利息或手续费——借呗是利息、信用卡分期是手续费，
 * 所以这里一律叫「息费」。合并成一个字段就再也对不回账单，所以宁可多占一行，也要把两个口径都摊开。
 */
@Composable
private fun RemainingCard(loan: Loan) {
    val principal = loan.unpaidPrincipalCents ?: loan.principalOutstandingCents ?: return
    val fee = loan.unpaidFeeCents
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        KeyValue("待还本金（不含息费）", Money.formatWithSymbol(principal))
        Spacer(Modifier.height(5.dp))
        if (fee != null) {
            KeyValue("待还息费", Money.format(fee))
        } else {
            KeyValue("未付息费（差额）", Money.format((loan.remainingPayableCents - principal).coerceAtLeast(0L)))
        }
        // 银行页写的「剩余未摊销金额」只含还没出账的那一期，和上面两行不是同一个口径，
        // 不解释清楚就会被当成「未还本金」，然后拿它去减剩余应还，算出一块不存在的息费。
        val unbilled = loan.principalOutstandingCents
        if (loan.statementDay != null && unbilled != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = "银行口径的「剩余未摊销本金」${Money.formatWithSymbol(unbilled)}，只含还没出账的那一期，" +
                    "上面的待还本金按全部待还期数摊算，两者不用互相对账。",
                color = LocalFqColors.current.muted,
                style = TextStyle(fontSize = FqLabel),
            )
        }
    }
}

@Composable
private fun ReadoutCard(loan: Loan, period: Period?, viewModel: AppViewModel, today: LocalDate) {
    val fq = LocalFqColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(fq.surface)
            .border(1.dp, fq.line, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        KeyValue("选中", period?.let { "第 ${it.index} / ${loan.totalPeriods} 期" } ?: "—")
        Spacer(Modifier.height(5.dp))
        KeyValue("应还日", period?.dueDate?.toString() ?: "—")
        Spacer(Modifier.height(5.dp))
        KeyValue("金额", period?.let { Money.formatWithSymbol(it.amountCents) } ?: "—")
        Spacer(Modifier.height(5.dp))
        KeyValue(
            key = "状态",
            value = when {
                period == null -> "—"
                period.paid -> "已还" + (period.paidOn?.let { "（$it）" } ?: "")
                period.dueDate.isBefore(today) -> "已逾期"
                else -> "待还"
            },
            valueColor = when {
                period?.paid == true -> fq.done
                period != null && period.dueDate.isBefore(today) -> fq.lateFg
                else -> null
            },
        )
        Spacer(Modifier.height(9.dp))
        period?.let {
            TextButton(
                text = if (it.paid) "撤销这一期的已还" else "标记本期已还",
                filled = !it.paid,
                onClick = { viewModel.togglePaid(loan.id, it.index, !it.paid) },
            )
        }
    }
}

@Composable
private fun PlanRow(period: Period, loan: Loan, today: LocalDate, onClick: () -> Unit) {
    val fq = LocalFqColors.current
    val label = when {
        period.paid -> "已还"
        period.dueDate.isBefore(today) -> "逾期"
        period.index == loan.nextUnpaid?.index -> "本期"
        else -> "待还"
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        DividerLine()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp).clickable(onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = period.index.toString(), color = fq.muted, style = TextStyle(fontSize = FqBodySmall), modifier = Modifier.width(30.dp))
            Text(text = period.dueDate.toString(), color = fq.muted, style = TextStyle(fontSize = FqBodySmall), modifier = Modifier.weight(1f))
            Text(text = Money.formatWithSymbol(period.amountCents), style = TextStyle(fontSize = FqBodySmall))
            Text(
                text = label,
                color = when (label) {
                    "已还" -> fq.done
                    "逾期" -> fq.lateFg
                    "本期" -> fq.nowFg
                    else -> fq.muted
                },
                style = TextStyle(fontSize = FqLabel),
                modifier = Modifier.width(34.dp),
            )
        }
    }
}
