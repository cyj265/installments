package com.cyj265.installments.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.cyj265.installments.data.Money
import com.cyj265.installments.data.VisionRecognizer
import com.cyj265.installments.domain.DueItem
import com.cyj265.installments.domain.Portfolio
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Date
import java.util.Locale

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
    // 导入时标的已还期没有还款日期（paidOn 为空），只认 paidOn 会让「本月已还」永远是 0。
    // 退到应还日：应还日落在本月且已还清，就算本月还掉的——没有真实日期时这是唯一可用的锚。
    val paidThisMonth = state.loans.sumOf { loan ->
        loan.periods.filter { it.paid && YearMonth.from(it.paidOn ?: it.dueDate) == YearMonth.from(state.today) }
            .sumOf { it.amountCents }
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
                    body = "把花呗、白条、借呗、银行账单的分期页截图选进来。同一笔如果一页读不全，把「借款详情」和逐笔列出的「应还款详情」一起选上，最多 4 张。读出后先给你核对，确认才入库。",
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
        // FAB 浮在列表上方（底边 84dp + 高 52dp），这里的留白必须比它高，
        // 否则滑到底时最后几行的金额会被「＋ / 粘贴 JSON」压住。
        item { Spacer(Modifier.height(140.dp)) }
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
    // 跟着正在看的月份走：翻到 11 月时这里必须变成 11 月的数，否则「本月合计」会跟日历对不上。
    val monthTotal = remember(dues) { dues.values.flatten().sumOf { it.period.amountCents } }
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
                                    // 上月/下月的日子要淡，但不能用 tickIdle：那是块底色，
                                    // 拿来当字色在两套主题上都只有 1.2~1.7:1，数字等于没画。
                                    // 淡一级交给 muted，和当月 ink 拉开即可。
                                    !inMonth -> fq.muted
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
            Metric(Money.formatWithSymbol(monthTotal), "${month.monthValue} 月合计", Modifier.weight(1f))
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
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(info.lastUpdateTime))
            "${info.versionName ?: "0.1.0"} · 装于 $stamp"
        }.getOrDefault("0.1.0")
    }
    // 配置项不是 StateFlow，直接绑到 SharedPreferences 会出现"打字看不见"的问题，所以先落本地状态再写回
    var baseUrl by remember { mutableStateOf(viewModel.settings.baseUrl) }
    var model by remember { mutableStateOf(viewModel.settings.model) }
    var apiKey by remember { mutableStateOf(viewModel.settings.apiKey) }
    var promptOpen by remember { mutableStateOf(false) }
    val ready = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.WRITE_CALENDAR] == true) {
            viewModel.setCalendarSync(true)
        } else {
            viewModel.showMessage("没给日历权限，写不进去。可以去系统设置 → 应用 → 分期管家 → 权限里打开「日历」")
        }
    }
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
            ActionRow(
                "检测连接",
                if (state.busy) "正在检测…" else "用当前地址、模型和 Key 发一条测试请求",
                enabled = !state.busy && ready,
                onClick = { viewModel.testConnection() },
            )
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
            val copyPrompt = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("识别指令", VisionRecognizer.PROMPT))
                viewModel.showMessage("指令已复制。模型回答的 JSON 原样贴回「粘贴 JSON 导入」即可")
            }
            // 指令有两三千字，复制按钮必须挂在行上：点开再滚到底才复制得了，等于每次都要翻一遍
            ActionRow(
                "发给模型的指令",
                if (promptOpen) "收起" else "没有 Key 时，把这段和截图一起发给任意视觉模型",
                onClick = { promptOpen = !promptOpen },
                trailing = { TextButton(text = "复制", onClick = copyPrompt) },
            )
            if (promptOpen) {
                AlertBox(
                    text = VisionRecognizer.PROMPT,
                    kind = ChipKind.NEUTRAL,
                    modifier = Modifier.padding(top = 10.dp),
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
            SectionTitle("提醒", if (state.calendarEnabled) "已开启" else "关闭")
            ActionRow(
                "写入本机日历",
                if (state.calendarEnabled) {
                    "已写入 ${state.calendarCount} 期未还还款日 · 点此关闭并清理"
                } else {
                    "每期还款日写进系统日历，当天早上 9 点提醒"
                },
                onClick = {
                    if (state.calendarEnabled) {
                        viewModel.setCalendarSync(false)
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.setCalendarSync(true)
                    } else {
                        calendarPermission.launch(
                            arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
                        )
                    }
                },
            )
            AlertBox(
                text = "在 App 里点「已还」，日历里那一期会自动删掉；改金额、改日期也会跟着更新。" +
                    "反过来不行：Android 没有公开的待办完成接口，在日历里打勾 App 读不到——你在日历里手动删掉的事件，下次打开 App 会提示一次，由你自己确认是否已还。",
                modifier = Modifier.padding(top = 10.dp),
            )
            if (state.calendarEnabled) {
                SettingText("检测到的日历", state.calendarSummary ?: "—")
                state.calendarProblem?.let {
                    AlertBox(text = it, kind = ChipKind.LATE, modifier = Modifier.padding(top = 10.dp))
                }
            }
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
private fun ActionRow(label: String, hint: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null, enabled: Boolean = true) {
    val fq = LocalFqColors.current
    Column(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
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
            trailing?.invoke()
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
