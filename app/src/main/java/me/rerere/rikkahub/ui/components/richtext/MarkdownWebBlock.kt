package me.rerere.rikkahub.ui.components.richtext

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState

@Composable
fun MarkdownWebBlock(
    content: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    var heightDp by remember { mutableStateOf(40.dp) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    val jsInterface = remember {
        object {
            @JavascriptInterface
            fun onHeightChanged(heightPx: Int) {
                handler.post {
                    val dpVal = heightPx.dp
                    if (dpVal > 10.dp && dpVal != heightDp) {
                        heightDp = dpVal
                    }
                }
            }
        }
    }

    val htmlContent = remember(content, colorScheme) {
        buildMarkdownPreviewHtml(
            context = context,
            markdown = content,
            colorScheme = colorScheme
        )
    }

    val webViewState = rememberWebViewState(
        data = htmlContent,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(
            "AndroidInterface" to jsInterface
        ),
        settings = {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }
    )

    WebView(
        state = webViewState,
        onCreated = { webView ->
            webView.isHorizontalScrollBarEnabled = false
            webView.isVerticalScrollBarEnabled = false
            webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        },
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
    )
}
