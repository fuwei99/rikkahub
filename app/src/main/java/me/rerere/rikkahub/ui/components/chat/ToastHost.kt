package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.event.ToastLevel
import org.koin.compose.koinInject

/** 同屏最多堆几个，超了从最老的开始挤掉。 */
private const val MAX_VISIBLE_TOASTS = 3

/**
 * 全局浮层提示宿主（2026-09-19）。
 *
 * 挂在 RouteActivity 顶层，和 [AskUserDialogHost] / [AppealDialogHost] 一个待遇：
 * 任何页面都能盖住。
 *
 * ## 为什么不用 AlertDialog
 *
 * 弹窗抢焦点、要人点确认，属于「打断」。Toast 的语义是「告知但不打断」——
 * 从顶部压下来、几秒后自己走、点一下也能提前关。所以这里是自己画的 Surface
 * 浮层，不是 Dialog。
 *
 * ## 生命周期为什么用 expireAt 而不是 duration
 *
 * `delay(duration)` 挂在 LaunchedEffect 上，宿主一重组 / 一旋转协程就重启，
 * 倒计时归零重来。改成传**绝对到期时刻**，每次重组都按 `expireAt - now` 重算剩余，
 * 重建多少次都准。（同源教训：2026-09-19 申诉弹窗那个自杀式 cancel。）
 *
 * ## 后台怎么办
 *
 * 这里只管前台。app 在后台时浮层根本不可见 —— 那条路由
 * [me.rerere.rikkahub.service.ChatNotificationManager] 的系统通知兜底。
 */
@Composable
fun ToastHost(
    eventBus: AppEventBus = koinInject(),
) {
    val toasts = remember { mutableStateListOf<AppEvent.ToastPending>() }

    LaunchedEffect(Unit) {
        eventBus.events.collect { event ->
            when (event) {
                is AppEvent.ToastPending -> {
                    // 同 id 重投 = 刷新内容，不是叠一层
                    toasts.removeAll { it.toastId == event.toastId }
                    toasts.add(event)
                    while (toasts.size > MAX_VISIBLE_TOASTS) {
                        toasts.removeAt(0)
                    }
                }

                is AppEvent.ToastDismissed -> toasts.removeAll { it.toastId == event.toastId }

                else -> Unit
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            toasts.forEach { toast ->
                key(toast.toastId) {
                    ToastCard(
                        toast = toast,
                        onDismiss = {
                            toasts.removeAll { it.toastId == toast.toastId }
                            eventBus.tryEmit(AppEvent.ToastDismissed(toast.toastId))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToastCard(
    toast: AppEvent.ToastPending,
    onDismiss: () -> Unit,
) {
    // 绝对到期时刻：重组多少次都按剩余时间重算，不依赖协程存活
    LaunchedEffect(toast.toastId, toast.expireAt) {
        if (toast.expireAt > 0L) {
            val remaining = toast.expireAt - System.currentTimeMillis()
            if (remaining > 0L) delay(remaining)
            onDismiss()
        }
    }

    val accent = toastAccent(toast.level)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onDismiss),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧色条：级别一眼可辨，比塞 emoji 干净
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .width(3.dp)
                    .height(30.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                val title = toast.title?.takeIf { it.isNotBlank() }
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
                Text(
                    text = toast.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val source = toast.source?.takeIf { it.isNotBlank() }
                if (source != null) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun toastAccent(level: String): Color = when (ToastLevel.normalize(level)) {
    ToastLevel.SUCCESS -> Color(0xFF2E7D32)
    ToastLevel.WARN -> Color(0xFFE65100)
    ToastLevel.ERROR -> Color(0xFFC62828)
    else -> Color(0xFF1565C0)
}
