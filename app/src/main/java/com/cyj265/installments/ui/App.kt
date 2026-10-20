package com.cyj265.installments.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { OVERVIEW, CALENDAR, MINE, DETAIL, REVIEW }

/** 一次最多选 4 张：详情 + 还款计划 + 放款凭证足够拼出一笔，再多模型容易把两笔串成一条。 */
private const val MAX_SHOTS = 4

@Composable
fun InstallmentsApp(viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)) {
    val state by viewModel.state.collectAsState()
    val fq = LocalFqColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 路由用字符串存，配置变更时 rememberSaveable 不需要 Parcelable
    var routeName by rememberSaveable { mutableStateOf(Screen.OVERVIEW.name) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    val route = runCatching { Screen.valueOf(routeName) }.getOrDefault(Screen.OVERVIEW)

    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(3000)
            viewModel.consumeToast()
        }
    }

    // 识别或粘贴产出新草稿时自动进入确认页；只有条数变化才触发，
    // 所以用户主动退回总览后不会被反复拽回来，横幅还在，随时能回去继续。
    LaunchedEffect(state.drafts.size) {
        if (state.drafts.isNotEmpty() && route == Screen.OVERVIEW) routeName = Screen.REVIEW.name
    }

    // 支持一次选多张：同一笔的「借款详情」和「还款计划」各缺一半，分开识别就只能人肉补
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_SHOTS)) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                runCatching { uris.map { readBytes(context, it) } }
                    .onSuccess { viewModel.recognize(it) }
                    .onFailure { viewModel.showMessage("有图片读不出来：${it.message}") }
            }
        }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { writeText(context, uri, viewModel.exportText()) }
                    .onSuccess { viewModel.showMessage("备份已写出（不含 API Key）") }
                    .onFailure { viewModel.showMessage("导出失败：${it.message}") }
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { readBytes(context, uri) }
                    .onSuccess { viewModel.restore(it.toString(Charsets.UTF_8)) }
                    .onFailure { viewModel.showMessage("读不到备份文件：${it.message}") }
            }
        }
    }

    val secondary = route == Screen.DETAIL || route == Screen.REVIEW

    FqTheme {
        Box(modifier = Modifier.fillMaxSize().background(fq.paper).safeDrawingPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppBar(
                    route = route,
                    state = state,
                    detailId = detailId,
                    onBack = { routeName = Screen.OVERVIEW.name },
                )
                if (state.drafts.isNotEmpty() && route != Screen.REVIEW) {
                    PendingBanner(count = state.drafts.size) { routeName = Screen.REVIEW.name }
                }
                Box(modifier = Modifier.weight(1f)) {
                    when (route) {
                        Screen.OVERVIEW -> OverviewScreen(
                            state = state,
                            onOpenLoan = { detailId = it; routeName = Screen.DETAIL.name },
                            onEmptyImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        )

                        Screen.CALENDAR -> CalendarScreen(
                            state = state,
                            onOpenLoan = { detailId = it; routeName = Screen.DETAIL.name },
                        )

                        Screen.MINE -> SettingsScreen(
                            state = state,
                            viewModel = viewModel,
                            onPasteJson = { pasteOpen = true },
                            onExport = { createDocument.launch("loans-${LocalDate.now()}.json") },
                            onRestore = { openDocument.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        )

                        Screen.DETAIL -> DetailScreen(
                            loan = state.loans.firstOrNull { it.id == detailId },
                            state = state,
                            viewModel = viewModel,
                            onBack = { routeName = Screen.OVERVIEW.name },
                        )

                        Screen.REVIEW -> ReviewScreen(
                            state = state,
                            viewModel = viewModel,
                            onBack = { routeName = Screen.OVERVIEW.name },
                        )
                    }
                }
                if (!secondary) {
                    TabBar(
                        selected = when (route) {
                            Screen.CALENDAR -> 1
                            Screen.MINE -> 2
                            else -> 0
                        },
                        onSelect = { index ->
                            routeName = when (index) {
                                1 -> Screen.CALENDAR.name
                                2 -> Screen.MINE.name
                                else -> Screen.OVERVIEW.name
                            }
                        },
                    )
                }
            }

            if (route == Screen.OVERVIEW) {
                ImportFab(
                    busy = state.busy,
                    onShoot = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPaste = { pasteOpen = true },
                )
            }

            state.toast?.let { message ->
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (secondary) 24.dp else 96.dp).padding(horizontal = 18.dp),
                ) {
                    ToastBubble(message)
                }
            }
        }
    }

    if (pasteOpen) {
        PasteDialog(
            onDismiss = { pasteOpen = false },
            onSubmit = { text ->
                val before = viewModel.state.value.drafts.size
                viewModel.pasteJson(text)
                pasteOpen = false
                if (viewModel.state.value.drafts.size > before) routeName = Screen.REVIEW.name
            },
        )
    }
}

@Composable
private fun AppBar(route: Screen, state: AppUiState, detailId: String?, onBack: () -> Unit) {
    val fq = LocalFqColors.current
    val secondary = route == Screen.DETAIL || route == Screen.REVIEW
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secondary) {
            TextButton(text = "‹ 返回", onClick = onBack)
            Spacer(Modifier.width(6.dp))
        }
        val title = when (route) {
            Screen.OVERVIEW -> "分期管家"
            Screen.CALENDAR -> "还款日历"
            Screen.MINE -> "我的"
            Screen.REVIEW -> "确认识别结果"
            Screen.DETAIL -> state.loans.firstOrNull { it.id == detailId }?.institution ?: "分期详情"
        }
        Text(
            text = title,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        val trailing = when (route) {
            Screen.OVERVIEW -> "${state.loans.count { com.cyj265.installments.data.LoanStatus.ACTIVE == it.status }} 笔进行中"
            Screen.CALENDAR -> "${state.today.monthValue} 月 ${state.today.dayOfMonth} 日"
            Screen.REVIEW -> if (state.busy) "识别中…" else null
            else -> null
        }
        if (trailing != null) {
            Text(
                text = trailing,
                color = fq.muted,
                style = TextStyle(fontSize = FqLabel),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(fq.surface2).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PendingBanner(count: Int, onClick: () -> Unit) {
    val fq = LocalFqColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(fq.nowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "有 $count 条识别结果还没确认，点这里核对 ›",
            color = fq.nowFg,
            style = TextStyle(fontSize = FqBodySmall),
        )
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val fq = LocalFqColors.current
    val tabs = listOf(
        Triple("总览", Icons.Filled.Home, 0),
        Triple("日历", Icons.Filled.DateRange, 1),
        Triple("我的", Icons.Filled.Person, 2),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(fq.surface)
            .padding(top = 4.dp, bottom = 10.dp),
    ) {
        tabs.forEach { (label, icon, index) ->
            val sel = index == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(index) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(60.dp)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (sel) fq.nowBg else Color.Transparent),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (sel) fq.brand else fq.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = label,
                    color = if (sel) fq.brand else fq.muted,
                    style = TextStyle(fontSize = FqLabel),
                )
            }
        }
    }
}

/** FAB 只做一件事：选截图。没配 Key 时「粘贴 JSON」是同级的第二入口，不让用户去找。 */
@Composable
private fun ImportFab(busy: Boolean, onShoot: () -> Unit, onPaste: () -> Unit) {
    val fq = LocalFqColors.current
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 84.dp),
        ) {
            TextButton(text = if (busy) "识别中…" else "粘贴 JSON", enabled = !busy, onClick = onPaste)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (busy) fq.tickIdle else fq.brand)
                    .clickable(enabled = !busy, onClick = onShoot),
            ) {
                Text(text = "＋", color = fq.onBrand, style = TextStyle(fontSize = 24.sp))
            }
        }
    }
}

@Composable
private fun ToastBubble(text: String) {
    val fq = LocalFqColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(fq.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(text = text, color = fq.ink, style = TextStyle(fontSize = FqBodySmall))
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val fq = LocalFqColors.current
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(text = "解析", filled = true, enabled = text.isNotBlank(), onClick = { onSubmit(text) })
        },
        dismissButton = { TextButton(text = "取消", onClick = onDismiss) },
        title = { Text(text = "粘贴 JSON", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)) },
        text = {
            Column {
                Text(
                    text = "把模型输出的 JSON 原文贴进来（带说明文字或 ``` 代码块也能读）。要发给模型的指令原文在「我的 → 发给模型的指令」，可直接复制。解析出的条目仍要在确认页核对，不会直接入库。",
                    color = fq.muted,
                    style = TextStyle(fontSize = FqBodySmall),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(fq.paper)
                        .padding(8.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(color = fq.ink, fontSize = FqBodySmall),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    )
                }
            }
        },
    )
}

private suspend fun readBytes(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("内容提供器没有返回数据")
}

private suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        ?: error("无法写入所选文件")
}
