package com.cardvault.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cardvault.app.network.AppUpdateInfo

/**
 * 应用内更新弹窗：渐变卡头承载版本号（呼应卡面流光），可滚动的变更记录，
 * 应用内下载并交给系统安装器。仅在 Available / Downloading / ReadyToInstall 三态展示。
 */
@Composable
fun UpdateAvailableDialog(
    state: AppUpdateState,
    currentVersion: String,
    onDownload: (AppUpdateInfo) -> Unit,
    onInstall: (String) -> Unit,
    onOpenInBrowser: (AppUpdateInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val info = when (state) {
        is AppUpdateState.Available -> state.info
        is AppUpdateState.Downloading -> state.info
        is AppUpdateState.ReadyToInstall -> state.info
        else -> return
    }
    val downloading = state as? AppUpdateState.Downloading

    Dialog(
        onDismissRequest = { if (downloading == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                scaleIn(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.90f),
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            ) {
                Column {
                    UpdateHeader(
                        version = info.latestVersion,
                        currentVersion = currentVersion,
                        sizeBytes = info.assetSizeBytes,
                    )

                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "更新内容",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        ReleaseNotesPane(info.releaseNotes)
                    }

                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 16.dp)) {
                        if (downloading != null) {
                            DownloadProgress(downloading.downloadedBytes, downloading.totalBytes)
                            Spacer(Modifier.height(14.dp))
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                enabled = downloading == null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (downloading != null) "下载中" else "稍后")
                            }
                            Button(
                                onClick = {
                                    when (state) {
                                        is AppUpdateState.Available -> onDownload(info)
                                        is AppUpdateState.ReadyToInstall -> onInstall(state.apkPath)
                                        else -> {}
                                    }
                                },
                                enabled = downloading == null,
                                modifier = Modifier.weight(1.6f),
                            ) {
                                Text(
                                    when (state) {
                                        is AppUpdateState.Downloading -> "下载中…"
                                        is AppUpdateState.ReadyToInstall -> "立即安装"
                                        else -> "下载并安装"
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        if (downloading == null) {
                            TextButton(
                                onClick = { onOpenInBrowser(info) },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) {
                                Text(
                                    "改用浏览器下载",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 渐变卡头：版本号像卡面浮雕数字，叠一道静态斜向流光 */
@Composable
private fun UpdateHeader(version: String, currentVersion: String, sizeBytes: Long) {
    val cs = MaterialTheme.colorScheme
    val base = Brush.linearGradient(
        listOf(cs.primary, lerp(cs.primary, Color.Black, 0.22f)),
    )
    val sheen = Brush.linearGradient(
        0.0f to Color.White.copy(alpha = 0f),
        0.44f to Color.White.copy(alpha = 0f),
        0.50f to Color.White.copy(alpha = 0.18f),
        0.56f to Color.White.copy(alpha = 0f),
        1.0f to Color.White.copy(alpha = 0f),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .background(base),
    ) {
        Box(Modifier.matchParentSize().background(sheen))
        Column(Modifier.padding(start = 24.dp, top = 22.dp, end = 24.dp, bottom = 20.dp)) {
            Text(
                "发现新版本",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
                color = cs.onPrimary.copy(alpha = 0.82f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                version,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
                color = cs.onPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("当前 ")
                    append(currentVersion)
                    if (sizeBytes > 0) {
                        append(" · ")
                        append(formatMb(sizeBytes))
                    }
                },
                fontSize = 12.sp,
                color = cs.onPrimary.copy(alpha = 0.78f),
            )
        }
    }
}

/** 可滚动的变更记录容器，底部渐隐提示内容更长 */
@Composable
private fun ReleaseNotesPane(markdown: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.heightIn(max = 300.dp)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            if (markdown.isBlank()) {
                Text(
                    "本次更新暂无更新说明。",
                    fontSize = 14.sp,
                    color = cs.onSurfaceVariant,
                )
            } else {
                val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
                blocks.forEach { RenderBlock(it) }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(cs.surface.copy(alpha = 0f), cs.surface),
                    )
                ),
        )
    }
}

@Composable
private fun DownloadProgress(downloaded: Long, total: Long) {
    val cs = MaterialTheme.colorScheme
    val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    Column {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                trackColor = cs.primary.copy(alpha = 0.16f),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                trackColor = cs.primary.copy(alpha = 0.16f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (fraction != null) "正在下载 ${(fraction * 100).toInt()}%" else "正在下载…",
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
            )
            Text(
                if (total > 0) "${formatMb(downloaded)} / ${formatMb(total)}" else formatMb(downloaded),
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

// ---- 轻量 Markdown 渲染：标题、无序/有序列表、**加粗**、`代码`，覆盖常见发布说明 ----

private sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Bullet(val text: String, val indent: Int) : MdBlock
    data class Numbered(val number: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Space : MdBlock
}

@Composable
private fun RenderBlock(block: MdBlock) {
    val cs = MaterialTheme.colorScheme
    when (block) {
        is MdBlock.Heading -> Text(
            parseInline(block.text),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = cs.primary,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        is MdBlock.Bullet -> Row(
            Modifier.padding(start = (block.indent * 16).dp, top = 3.dp, bottom = 3.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 8.dp, end = 10.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = 0.7f)),
            )
            Text(
                parseInline(block.text),
                fontSize = 14.sp,
                color = cs.onSurface,
                lineHeight = 21.sp,
            )
        }
        is MdBlock.Numbered -> Row(Modifier.padding(top = 3.dp, bottom = 3.dp)) {
            Text(
                "${block.number}.",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                parseInline(block.text),
                fontSize = 14.sp,
                color = cs.onSurface,
                lineHeight = 21.sp,
            )
        }
        is MdBlock.Paragraph -> Text(
            parseInline(block.text),
            fontSize = 14.sp,
            color = cs.onSurfaceVariant,
            lineHeight = 21.sp,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        MdBlock.Space -> Spacer(Modifier.height(8.dp))
    }
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val text = raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    val headingRegex = Regex("^#{1,6}\\s+.*")
    val bulletRegex = Regex("^[-*+]\\s+.*")
    val numberedRegex = Regex("^(\\d+)[.)]\\s+(.*)")
    val blocks = mutableListOf<MdBlock>()
    for (rawLine in text.split("\n")) {
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> {
                if (blocks.isNotEmpty() && blocks.last() != MdBlock.Space) blocks.add(MdBlock.Space)
            }
            headingRegex.matches(trimmed) ->
                blocks.add(MdBlock.Heading(trimmed.trimStart('#').trim()))
            bulletRegex.matches(trimmed) -> {
                val indent = ((line.length - line.trimStart().length) / 2).coerceIn(0, 2)
                blocks.add(MdBlock.Bullet(trimmed.replaceFirst(Regex("^[-*+]\\s+"), ""), indent))
            }
            numberedRegex.matches(trimmed) -> {
                val m = numberedRegex.find(trimmed)!!
                blocks.add(MdBlock.Numbered(m.groupValues[1], m.groupValues[2]))
            }
            else -> blocks.add(MdBlock.Paragraph(trimmed))
        }
    }
    while (blocks.firstOrNull() == MdBlock.Space) blocks.removeAt(0)
    while (blocks.lastOrNull() == MdBlock.Space) blocks.removeAt(blocks.lastIndex)
    return blocks
}

private fun parseInline(text: String): AnnotatedString {
    val cleaned = text.replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "$1")
    val regex = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`(.+?)`")
    return buildAnnotatedString {
        var last = 0
        regex.findAll(cleaned).forEach { mr ->
            if (mr.range.first > last) append(cleaned.substring(last, mr.range.first))
            val bold = mr.groupValues[1].ifEmpty { mr.groupValues[2] }
            val code = mr.groupValues[3]
            when {
                bold.isNotEmpty() ->
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
                code.isNotEmpty() ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(code) }
            }
            last = mr.range.last + 1
        }
        if (last < cleaned.length) append(cleaned.substring(last))
    }
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)
