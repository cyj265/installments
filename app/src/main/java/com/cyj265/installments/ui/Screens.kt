package com.cyj265.installments.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyj265.installments.data.Money
import com.cyj265.installments.data.VisionRecognizer
import com.cyj265.installments.domain.DueItem
import com.cyj265.installments.domain.Portfolio
import java.time.LocalDate
import java.time.YearMonth

/** 屏 1：总览。默认落地页，负债全貌 + 进行中的分期。 */
@Composable
fun OverviewScreen(
    state: AppUiState,
    onOpenLoan: (String) -> Unit,
    onEmptyImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fq = LocalFqColors.current
    val summary = remember(state.loans, state.today) { Portfolio.summarize(state.loans, state.today) }
    val active = remember(state.loans, state.today) {
        Portfolio.activeLoans(state.loans).sortedWith(
            compareBy({ if (it.isOverdue(state.today)) 0 else 1 }, { it.nextUnpaid?.dueDate ?: it.periods.last().dueDate }),
        )
    }
    val settled = state.loans.filter { active.none { a -> a.id == it.id } }
    val paidThisMonth = state.loans.sumOf { loan ->
        loan.periods.count { it.paid && it.paidOn != null && YearMonth.from(it.paidOn) == YearMonth.from(state.today) }
            .toLong() * loan.perPeriodAmountCents
    }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(10.dp))
            Text(text = "剩余总负债", color = fq.muted, style = TextStyle(fontSize = FqLabel))
            Text(
                text = Money.formatWithSymbol(summary.remainingPayableCents),
                style = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
            )
            Text(
                text = "本月已还 ${Money.formatWithSymbol(paidThisMonth)} · 本月还剩 ${summary.monthPayableCount} 笔",
                color = fq.muted,
                style = TextStyle(fontSize = FqLabel),
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Metric(Money.formatWithSymbol(summary.monthPayableCents), "本月应还", Modifier.weight(1f))
                Metric(
                    summary.daysToNextDue?.let { "$it 天后" } ?: "—",
                    summary.nextDue?.let { "下期 ${monthDay(it.period.dueDate)}" } ?: "没有待还",
                    Modifier.weight(1f),
                    divider = true,
                )
                Metric(
                    summary.finalSettleDate?.let { "%d-%02d".format(it.year, it.monthValue) } ?: "—",
                    "全部还清",
                    Modifier.weight(1f),
                    divider = true,
                )
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "总进度 ${summary.paidPeriods} / ${summary.totalPeriods} 期", color = fq.muted, style = TextStyle(fontSize = FqLabel))
                Text(
                    text = if (summary.totalPeriods > 0) "${summary.paidPeriods * 100 / summary.totalPeriods}%" else "—",
                    color = fq.muted,
                    style = TextStyle(fontSize = FqLabel),
                )
            }
            Spacer(Modifier.height(4.dp))
            ThinBar(if (summary.totalPeriods > 0) summary.paidPeriods.toFloat() / summary.totalPeriods else 0f)
        }

        if (state.loans.isEmpty()) {
            item {
                EmptyState(
                    title = "还没有分期记录",
                    body = "把花呗、白条、银行账单的分期页截图选进来。同一笔如果一页读不全，把「借款详情」和「还款计划」一起选上，最多 4 张。读出后先给你核对，确认才入库。",
                    action = { TextButton(text = "选截图导入", filled = true, onClick = onEmptyImport) },
                )
            }
        } else {
            item { SectionTitle("进行中 · ${active.size} 笔", "按还款日排") }
            active.forEach { loan ->
                item(key = loan.id) { LoanRow(loan, state.today) { onOpenLoan(loan.id) } }
            }
            if (settled.isNotEmpty()) {
                item { SectionTitle("已结束 · ${settled.size} 笔") }
                settled.forEach { loan ->
                    item(key = "s-${loan.id}") { LoanRow(loan, state.today) { onOpenLoan(loan.id) } }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
internal fun Metric(value: String, label: String, modifier: Modifier = Modifier, divider: Boolean = false) {
    val fq = LocalFqColors.current
    Row(modifier = modifier.height(IntrinsicSize.Min)) {
        if (divider) Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(fq.line))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (divider) 10.dp else 0.dp)
                .padding(vertical = 2.dp),
        ) {
            Text(text = value, style = TextStyle(fontSize = FqBody, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(text = label, color = fq.muted, style = TextStyle(fontSize = FqLabel), maxLines = 1)
        }
    }
}

/** 屏 3：还款日历。看"哪天要掏多少钱"，点某天看当日明细。 */
@Composable
fun CalendarScreen(
    state: AppUiState,
    onOpenLoan: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fq = LocalFqColors.current
    var monthKey by rememberSaveable(state.today) { mutableStateOf(YearMonth.from(state.today).toString()) }
    val month = runCatching { YearMonth.parse(monthKey) }.getOrDefault(YearMonth.from(state.today))
    val gridStart = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value - 1).toLong())
    val days = remember(month) { (0 until 42).map { gridStart.plusDays(it.toLong()) } }
    val dues = remember(state.loans, month) {
        Portfolio.dues(state.loans, month.atDay(1), month.atEndOfMonth())
    }
    var selected by rememberSaveable(state.today) { mutableStateOf(state.today.toString()) }
    val selectedDate = runCatching { LocalDate.parse(selected) }.getOrDefault(state.today)
    val items = dues[selectedDate].orEmpty()
    val summary = remember(state.loans, state.today) { Portfolio.summarize(state.loans, state.today) }
    val next90 = remember(state.loans, state.today) {
        Portfolio.dues(state.loans, state.today, state.today.plusDays(90)).values.flatten().sumOf { it.period.amountCents }
    }
    val nextMonthCount = remember(state.loans, month) {
        Portfolio.dues(state.loans, month.plusMonths(1).atDay(1), month.plusMonths(1).atEndOfMonth()).values.sumOf { it.size }
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            MonthArrow("‹") { monthKey = month.minusMonths(1).toString() }
            Text(text = "${month.year} 年 ${month.monthValue} 月", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
            MonthArrow("›") { monthKey = month.plusMonths(1).toString() }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(
                    text = it,
                    color = fq.muted,
                    style = TextStyle(fontSize = FqLabel),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    val inMonth = YearMonth.from(day) == month
                    val dayItems = dues[day].orEmpty()
                    val hasLate = dayItems.isNotEmpty() && day.isBefore(state.today)
                    val isSel = day == selectedDate
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isSel) fq.brand else Color.Transparent)
                            .clickable { selected = day.toString() },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = day.dayOfMonth.toString(),
                                color = when {
                                    isSel -> fq.onBrand
                                    !inMonth -> fq.tickIdle
                                    else -> fq.ink
                                },
                                style = TextStyle(fontSize = FqBodySmall),
                            )
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            dayItems.isEmpty() -> Color.Transparent
                                            isSel -> fq.onBrand
                                            hasLate -> fq.lateFg
                                            else -> fq.nowFg
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
        SectionTitle(
            title = "${selectedDate.monthValue} 月 ${selectedDate.dayOfMonth} 日 · ${items.size} 笔",
            trailing = Money.formatWithSymbol(items.sumOf { it.period.amountCents }),
        )
        if (items.isEmpty()) {
            Text(text = "这天没有要还的", color = fq.muted, style = TextStyle(fontSize = FqBodySmall), modifier = Modifier.padding(vertical = 8.dp))
        }
        items.forEach { item ->
            DueRow(item, state) { onOpenLoan(item.loanId) }
        }
        Spacer(Modifier.height(8.dp))
        DividerLine()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Metric(Money.formatWithSymbol(summary.monthPayableCents), "本月合计", Modifier.weight(1f))
            Metric(Money.formatWithSymbol(next90), "未来 90 天", Modifier.weight(1f), divider = true)
            Metric("$nextMonthCount 笔", "${month.plusMonths(1).monthValue} 月到期", Modifier.weight(1f), divider = true)
        }
    }
}

@Composable
private fun DueRow(item: DueItem, state: AppUiState, onClick: () -> Unit) {
    val fq = LocalFqColors.current
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        DividerLine()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.institution + (item.loanNo?.let { " $it" } ?: ""), style = TextStyle(fontSize = FqBodySmall))
                Text(
                    text = "第 ${item.period.index} / ${state.loans.firstOrNull { it.id == item.loanId }?.totalPeriods ?: "?"} 期" +
                        if (item.period.dueDate.isBefore(state.today)) " · 已逾期" else "",
                    color = if (item.period.dueDate.isBefore(state.today)) fq.lateFg else fq.muted,
                    style = TextStyle(fontSize = FqLabel),
                )
            }
            Text(text = Money.formatWithSymbol(item.period.amountCents), style = TextStyle(fontSize = FqBodySmall))
        }
    }
}

/** 屏 6：我的。识别服务、本机数据与备份。 */
@Composable
fun SettingsScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onPasteJson: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fq = LocalFqColors.current
    val summary = remember(state.loans, state.today) { Portfolio.summarize(state.loans, state.today) }
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0" }.getOrDefault("0.1.0")
    }
    // 配置项不是 StateFlow，直接绑到 SharedPreferences 会出现"打字看不见"的问题，所以先落本地状态再写回
    var baseUrl by remember { mutableStateOf(viewModel.settings.baseUrl) }
    var model by remember { mutableStateOf(viewModel.settings.model) }
    var apiKey by remember { mutableStateOf(viewModel.settings.apiKey) }
    var promptOpen by remember { mutableStateOf(false) }
    val ready = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Metric("${summary.activeCount} 笔", "进行中", Modifier.weight(1f))
                Metric(Money.formatWithSymbol(summary.remainingPayableCents), "剩余应还", Modifier.weight(1f), divider = true)
                Metric("${summary.paidPeriods} / ${summary.totalPeriods}", "已还期数", Modifier.weight(1f), divider = true)
            }
        }
        item {
            SectionTitle("识别服务", if (ready) "已就绪" else "未就绪")
            SettingsField("接口地址", baseUrl, onValueChange = { baseUrl = it; viewModel.settings.baseUrl = it })
            SettingsField("模型", model, onValueChange = { model = it; viewModel.settings.model = it })
            SettingsField(
                label = "API Key",
                value = apiKey,
                warning = apiKey.isBlank(),
                secret = true,
                placeholder = "粘贴 Key，留空也能用",
                onValueChange = { apiKey = it; viewModel.settings.apiKey = it },
            )
            AlertBox(
                text = if (apiKey.isBlank()) {
                    "还没填 Key。不填也能用：点下面「发给模型的指令」把那段话复制走，配着截图发给任意一个视觉模型，再把它回的 JSON 原样贴进本页的「粘贴 JSON 导入」。"
                } else {
                    "Key 只写进本机应用私有目录，不进 Git、不随备份导出，这一格默认遮住、点「显示」才展开。这份 APK 若要发给别人，必须先在这里清空，让对方填自己的免费 Key。"
                },
                modifier = Modifier.padding(top = 10.dp),
            )
            ActionRow(
                "发给模型的指令",
                if (promptOpen) "收起" else "没有 Key 时，把这段和截图一起发给任意视觉模型",
                onClick = { promptOpen = !promptOpen },
            )
            if (promptOpen) {
                AlertBox(
                    text = VisionRecognizer.PROMPT,
                    kind = ChipKind.NEUTRAL,
                    modifier = Modifier.padding(top = 10.dp),
                    actions = {
                        TextButton(text = "复制这段指令", onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("识别指令", VisionRecognizer.PROMPT))
                            viewModel.showMessage("指令已复制。模型回答的 JSON 原样贴回「粘贴 JSON 导入」即可")
                        })
                    },
                )
            }
        }
        item {
            SectionTitle("数据", "本机存储")
            ActionRow("粘贴 JSON 导入", "把模型给的 JSON 原文贴进来", onClick = onPasteJson)
            ActionRow("导出备份", "loans.json", onClick = onExport)
            ActionRow("从备份恢复", "覆盖当前记录", onClick = onRestore)
            SettingText("数据文件", state.loans.size.let { "$it 笔 / ${summary.totalPeriods} 期" })
            SettingText("路径", viewModel.dataFilePath())
        }
        item {
            SectionTitle("提醒", "第二期")
            SettingText("还款日提醒", "关闭")
            SettingText("版本", version)
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    warning: Boolean = false,
    secret: Boolean = false,
    placeholder: String = "未填入",
) {
    FqField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        warning = warning,
        secret = secret,
        placeholder = placeholder,
    )
}

@Composable
private fun SettingText(label: String, value: String) {
    val fq = LocalFqColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = label, color = fq.muted, style = TextStyle(fontSize = FqBodySmall), modifier = Modifier.width(88.dp))
        Text(
            text = value,
            color = fq.ink,
            style = TextStyle(fontSize = FqBodySmall),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
    }
    DividerLine()
}

@Composable
private fun ActionRow(label: String, hint: String, onClick: () -> Unit) {
    val fq = LocalFqColors.current
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        DividerLine()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = TextStyle(fontSize = FqBody))
                Text(text = hint, color = fq.muted, style = TextStyle(fontSize = FqLabel))
            }
            Text(text = "›", color = fq.muted, style = TextStyle(fontSize = FqBody))
        }
    }
}

@Composable
fun AlertBox(
    text: String,
    modifier: Modifier = Modifier,
    kind: ChipKind = ChipKind.NEUTRAL,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val fq = LocalFqColors.current
    val (bg, fg) = when (kind) {
        ChipKind.LATE -> fq.lateBg to fq.lateFg
        ChipKind.NOW -> fq.nowBg to fq.nowFg
        ChipKind.NEUTRAL -> fq.surface2 to fq.ink
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(if (kind == ChipKind.NEUTRAL) Modifier.border(1.dp, fq.line, RoundedCornerShape(10.dp)) else Modifier)
            .padding(10.dp),
    ) {
        Text(text = text, color = fg, style = TextStyle(fontSize = FqBodySmall))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = actions,
        )
    }
}

@Composable
private fun MonthArrow(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Text(text = label, style = TextStyle(fontSize = FqBody))
    }
}
