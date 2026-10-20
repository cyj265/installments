package com.cyj265.installments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyj265.installments.data.Loan
import com.cyj265.installments.data.LoanStatus
import com.cyj265.installments.data.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 界面上一律用 "10-28" 这种短写，完整日期只在详情页出现。 */
fun monthDay(date: LocalDate): String = "%02d-%02d".format(date.monthValue, date.dayOfMonth)

internal val FqBody = 13.sp
internal val FqBodySmall = 12.sp
internal val FqLabel = 11.sp
internal val FqTitle = 15.sp

/**
 * 期数进度：一格 = 一期。线性进度条被否掉的原因是它看不出"还剩几期"，
 * 而这里每一格都对应一次要掏钱的还款日，必须一眼数得出来。
 *
 * 三态：已还=墨绿、本期未还=琥珀、以后才到期=灰。逾期不改格子颜色，
 * 由整行左侧红条和"逾期 N 天"角标表达，避免一格同时承担两种含义。
 */
@Composable
fun Ticks(loan: Loan, modifier: Modifier = Modifier, height: Int = 6) {
    val fq = LocalFqColors.current
    val nextUnpaid = loan.nextUnpaid?.index
    Row(
        modifier = modifier.height(height.dp),
        horizontalArrangement = Arrangement.spacedBy(if (height > 10) 4.dp else 3.dp),
    ) {
        loan.periods.forEach { period ->
            val color = when {
                period.paid -> fq.done
                period.index == nextUnpaid -> fq.nowFg
                else -> fq.tickIdle
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height.dp)
                    .clip(RoundedCornerShape(if (height > 10) 4.dp else 1.dp))
                    .background(color),
            )
        }
    }
}

/** 详情页里的可点格子：点一格 = 选中那一期，下方读数区再决定标记已还/未还。 */
@Composable
fun TickBlocks(
    loan: Loan,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fq = LocalFqColors.current
    val nextUnpaid = loan.nextUnpaid?.index
    Row(
        modifier = modifier.height(26.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        loan.periods.forEach { period ->
            val isCurrent = period.index == nextUnpaid && !period.paid
            val bg = when {
                period.paid -> fq.done
                isCurrent -> fq.nowBg
                else -> fq.tickIdle
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(bg)
                    .then(
                        if (isCurrent) Modifier.border(1.5.dp, fq.nowFg, RoundedCornerShape(4.dp)) else Modifier
                    )
                    .then(
                        if (period.index == selected) Modifier.border(2.dp, fq.brand, RoundedCornerShape(4.dp)) else Modifier
                    )
                    .clickable { onSelect(period.index) },
            ) {
                if (period.index == selected) {
                    Text(
                        text = period.index.toString(),
                        color = if (period.paid || isCurrent) fq.surface else fq.ink,
                        style = TextStyle(fontSize = FqLabel, fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

@Composable
fun OrgAvatar(name: String, modifier: Modifier = Modifier) {
    val fq = LocalFqColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(fq.brand),
    ) {
        Text(text = name.firstOrNull()?.toString() ?: "?", color = fq.onBrand, style = TextStyle(fontSize = FqBody))
    }
}

enum class ChipKind { LATE, NOW, NEUTRAL }

@Composable
fun StatusChip(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val fq = LocalFqColors.current
    val (bg, fg) = when (kind) {
        ChipKind.LATE -> fq.lateBg to fq.lateFg
        ChipKind.NOW -> fq.nowBg to fq.nowFg
        ChipKind.NEUTRAL -> fq.surface2 to fq.muted
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(text = text, color = fg, style = TextStyle(fontSize = FqLabel))
    }
}

@Composable
fun SectionTitle(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    val fq = LocalFqColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, color = fq.muted, style = TextStyle(fontSize = FqLabel))
        if (trailing != null) Text(text = trailing, color = fq.muted, style = TextStyle(fontSize = FqLabel))
    }
}

@Composable
fun ThinBar(progress: Float, modifier: Modifier = Modifier) {
    val fq = LocalFqColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(fq.tickIdle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(fq.done),
        )
    }
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    val fq = LocalFqColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = key, color = fq.muted, style = TextStyle(fontSize = FqBodySmall), modifier = Modifier.width(96.dp))
        Text(
            text = value,
            color = valueColor ?: fq.ink,
            style = TextStyle(fontSize = FqBodySmall),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(LocalFqColors.current.line))
}

@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val fq = LocalFqColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(if (filled) fq.brand else Color.Transparent)
            .border(1.dp, if (filled) fq.brand else fq.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> fq.muted
                filled -> fq.onBrand
                else -> fq.ink
            },
            style = TextStyle(fontSize = FqBodySmall),
        )
    }
}

@Composable
fun FqField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    trailing: String? = null,
    secret: Boolean = false,
    placeholder: String = "未识别到",
) {
    val fq = LocalFqColors.current
    // 默认遮住：这台机器可能是别人在用的，Key 明文摆在一行里太容易被瞟到
    var revealed by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, color = fq.muted, style = TextStyle(fontSize = FqBodySmall), modifier = Modifier.width(88.dp))
        OutlinedTextFieldStub(
            value = value,
            onValueChange = onValueChange,
            warning = warning,
            masked = secret && !revealed,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
        )
        when {
            secret -> Text(
                text = if (revealed) "隐藏" else "显示",
                color = fq.muted,
                style = TextStyle(fontSize = FqLabel),
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable { revealed = !revealed }
                    .padding(horizontal = 5.dp, vertical = 3.dp),
            )

            trailing != null -> Text(text = trailing, color = fq.muted, style = TextStyle(fontSize = FqLabel))
        }
    }
}

/**
 * 确认页里的单行输入。直接用 OutlinedTextField 会带出 Material 的标签动画与 56dp 最小高度，
 * 一屏十几行会把界面稿的紧凑排布撑散，所以这里保持一个朴素的输入框。
 */
@Composable
private fun OutlinedTextFieldStub(
    value: String,
    onValueChange: (String) -> Unit,
    warning: Boolean,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    placeholder: String = "未识别到",
) {
    val fq = LocalFqColors.current
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (warning) fq.lateBg else fq.surface)
            .border(1.dp, if (warning) fq.lateFg else fq.line, shape),
    ) {
        BasicTextFieldStub(
            value = value,
            onValueChange = onValueChange,
            color = if (warning) fq.lateFg else fq.ink,
            placeholder = placeholder,
            masked = masked,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun BasicTextFieldStub(
    value: String,
    onValueChange: (String) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    placeholder: String = "未识别到",
    masked: Boolean = false,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = color, fontSize = FqBodySmall),
        // 遮住时按密码框处理，顺带关掉自动首字母大写和纠错：
        // 这两项会把粘贴进去的 Key 改掉，而改错的 Key 只会得到一个 401。
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (masked) {
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            )
        } else {
            KeyboardOptions.Default
        },
        cursorBrush = androidx.compose.ui.graphics.SolidColor(color),
        modifier = modifier,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(text = placeholder, color = LocalFqColors.current.muted.copy(alpha = 0.7f), style = TextStyle(fontSize = FqBodySmall))
                }
                inner()
            }
        },
    )
}

/** 逾期行用左侧红条标记，比整行泛红更耐看，也不会和"本期"的琥珀撞色。 */
@Composable
fun LoanRow(loan: Loan, today: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fq = LocalFqColors.current
    val overdue = loan.isOverdue(today)
    val next = loan.nextUnpaid
    Column(modifier = modifier.clickable(onClick = onClick)) {
        DividerLine()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (overdue) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(fq.lateFg),
                )
            }
            OrgAvatar(loan.institution)
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = loan.institution,
                        style = TextStyle(fontSize = FqBody, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    when {
                        loan.status == LoanStatus.SETTLED -> Text(
                            text = "已结清",
                            color = fq.muted,
                            style = TextStyle(fontSize = FqLabel),
                        )
                        overdue -> StatusChip("逾期 ${loan.overdueDays(today)} 天", ChipKind.LATE)
                        else -> Text(
                            text = next?.let { "${monthDay(it.dueDate)} · ${ChronoUnit.DAYS.between(today, it.dueDate)} 天" } ?: "",
                            color = fq.muted,
                            style = TextStyle(fontSize = FqLabel),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Ticks(loan, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = if (loan.status == LoanStatus.SETTLED) "已还 ${loan.paidCount} / ${loan.totalPeriods} 期"
                        else "第 ${next?.index ?: loan.totalPeriods} / ${loan.totalPeriods} 期",
                        color = fq.muted,
                        style = TextStyle(fontSize = FqLabel),
                    )
                    Text(
                        text = "${Money.formatWithSymbol(loan.perPeriodAmountCents)} · 余 ${Money.format(loan.remainingPayableCents)}",
                        color = fq.ink,
                        style = TextStyle(fontSize = FqLabel),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    val fq = LocalFqColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(14, 22, 30, 40).forEachIndexed { i, h ->
                Box(
                    modifier = Modifier
                        .width(7.dp)
                        .height(h.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when (i) {
                                3 -> fq.done
                                2 -> fq.nowFg
                                else -> fq.tickIdle
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(text = title, style = TextStyle(fontSize = FqTitle, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            color = fq.muted,
            style = TextStyle(fontSize = FqBodySmall),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(260.dp),
        )
        Spacer(Modifier.height(16.dp))
        action()
        Spacer(Modifier.height(12.dp))
        Text(text = "数据只存在本机 · 可导出备份", color = fq.muted, style = TextStyle(fontSize = FqLabel))
    }
}
