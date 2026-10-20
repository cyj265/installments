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
    val chosen = loan.periods.firstOrNull { it.index == selected }
    val findings = remember(loan) { LoanValidator.validateLoan(loan) }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(6.dp))
            Text(text = "剩余应还合计（含手续费）", color = fq.muted, style = TextStyle(fontSize = FqLabel))
            Text(
                text = Money.formatWithSymbol(loan.remainingPayableCents),
                style = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = buildString {
                    append(loan.loanNo ?: "没有单号")
                    append(" · 每月 ${loan.dueDayOfMonth} 日")
                    append(" · 每期 ${Money.formatWithSymbol(loan.perPeriodAmountCents)}")
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
            PrincipalCard(loan)
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
 * 未还本金与剩余应还合计并列展示。两者相差的就是手续费，合并成一个字段就再也对不回账单，
 * 所以这里宁可多占一行，也要把两个口径都摊开。
 */
@Composable
private fun PrincipalCard(loan: Loan) {
    val principal = loan.principalOutstandingCents ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        KeyValue("未还本金（不含手续费）", Money.formatWithSymbol(principal))
        Spacer(Modifier.height(5.dp))
        KeyValue(
            "已还期数 × 每期 = 手续费差",
            Money.format((loan.remainingPayableCents - principal).coerceAtLeast(0L)),
        )
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
