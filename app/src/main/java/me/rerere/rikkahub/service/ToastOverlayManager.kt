package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.ToastLevel

/**
 * 系统级浮层（`TYPE_APPLICATION_OVERLAY`）。
 *
 * ## 和 ToastHost 的分工
 *
 * [me.rerere.rikkahub.ui.components.chat.ToastHost] 是 **app 内** 的 Compose 浮层，
 * 只在 Rikkahub 前台时可见。本类是 **系统级** 浮层：靠 `SYSTEM_ALERT_WINDOW` 权限
 * 把 View 直接挂到 `WindowManager` 上，**能盖在别的 app 之上** —— Rikkahub 切到后台
 * 也照样弹，就是 Termux `termux-toast` 那种「屏幕跳脸」的效果。
 *
 * 路由规则（见 [ChatNotificationManager.handleToastPending]）：
 * - 前台 → Compose 浮层（ToastHost），跟主题走，样式更精致
 * - 后台 + 有悬浮窗权限 → 本类
 * - 后台 + 没权限 → 回落到系统通知
 *
 * ## 为什么不用 ComposeView
 *
 * 把 Compose 塞进 WindowManager 需要自己造
 * LifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner 三件套，
 * 代码量大、编译风险高（本地无 Android SDK，只能靠 CI 兜底）。
 * overlay 卡片本来就只有「色条 + 标题 + 正文」，纯 View 足够，且零 Compose 依赖。
 *
 * ## 生命周期为什么用 expireAt
 *
 * 同 ToastHost：`postDelayed` 的延时按 `expireAt - now` 现算，不用 `delay(duration)`。
 * 绝对时刻是唯一真相，宿主重建 / 旋转都不会漂。
 *
 * ## 触摸
 *
 * 窗口只加 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`，**不加**
 * `FLAG_NOT_TOUCHABLE` —— 卡片本身要能点（点一下提前关）。
 * 窗口尺寸取 `WRAP_CONTENT`，所以除卡片外不占任何触摸区域，不会挡住下层 app。
 *
 * 单实例：同一时刻只挂一个卡片，新的挤掉旧的。
 */
class ToastOverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentView: View? = null
    private var currentId: String? = null
    private var currentTimeout: Runnable? = null

    /** 有没有悬浮窗权限。没有就只能回落到系统通知。 */
    fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (_: Throwable) {
        false
    }

    /**
     * 挂一个系统级浮层。没权限时静默返回（调用方负责回落）。
     *
     * @param onDismiss 卡片被点掉 / 到期自动收摊时回调，用来同步撤通知。
     */
    fun show(toast: AppEvent.ToastPending, onDismiss: (() -> Unit)? = null) {
        if (!canDrawOverlays()) return
        mainHandler.post {
            removeCurrent()

            val view = buildView(toast) { dismiss(toast.toastId, onDismiss) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }

            try {
                windowManager.addView(view, params)
            } catch (_: Throwable) {
                // 权限在检查后被撤销 / WindowManager 已销毁：直接放弃，别崩
                return@post
            }

            currentView = view
            currentId = toast.toastId

            if (toast.expireAt > 0L) {
                val remaining = (toast.expireAt - System.currentTimeMillis()).coerceAtLeast(0L)
                val timeout = Runnable { dismiss(toast.toastId, onDismiss) }
                currentTimeout = timeout
                mainHandler.postDelayed(timeout, remaining)
            }
        }
    }

    /** 按 id 撤。id 对不上（已经被新的挤掉）就什么都不做。 */
    fun dismiss(toastId: String, onDismiss: (() -> Unit)? = null) {
        mainHandler.post {
            val id = currentId
            if (id != null && id != toastId) return@post
            val had = currentView != null
            removeCurrent()
            if (had) onDismiss?.invoke()
        }
    }

    fun dismissAll() {
        mainHandler.post { removeCurrent() }
    }

    private fun removeCurrent() {
        currentTimeout?.let { mainHandler.removeCallbacks(it) }
        currentTimeout = null
        currentView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
                // 已经不在窗口树上，忽略
            }
        }
        currentView = null
        currentId = null
    }

    private fun buildView(toast: AppEvent.ToastPending, onClick: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val accent = overlayAccent(toast.level)

        // 左侧色条：级别一眼可辨，和 Compose 浮层保持同一套视觉语言
        val bar = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(30)).apply {
                rightMargin = dp(12)
            }
            setBackgroundColor(accent)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        toast.title?.takeIf { it.isNotBlank() }?.let { title ->
            column.addView(TextView(context).apply {
                text = title
                setTextColor(accent)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            })
        }

        column.addView(TextView(context).apply {
            text = toast.text
            setTextColor(Color.WHITE)
            textSize = 14f
        })

        toast.source?.takeIf { it.isNotBlank() }?.let { source ->
            column.addView(TextView(context).apply {
                text = source
                setTextColor(0xFFB0B0B0.toInt())
                textSize = 11f
            })
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(16), dp(12))
            // overlay 没有 Compose 的 MaterialTheme，硬编码一档深色卡片 + 白字，
            // 在深浅两种系统主题下都读得清
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF0_202124.toInt())
            }
            elevation = dp(8).toFloat()
            addView(bar)
            addView(column)
            setOnClickListener { onClick() }
        }
    }
}

/** 注意别和 ToastHost.kt 里的 `toastAccent` 重名：两个都是文件私有，但同名容易看混。 */
private fun overlayAccent(level: String): Int = when (ToastLevel.normalize(level)) {
    ToastLevel.SUCCESS -> 0xFF4CAF50.toInt()
    ToastLevel.WARN -> 0xFFFF9800.toInt()
    ToastLevel.ERROR -> 0xFFF44336.toInt()
    else -> 0xFF2196F3.toInt()
}
