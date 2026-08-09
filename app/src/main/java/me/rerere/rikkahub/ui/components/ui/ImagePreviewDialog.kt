package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/**
 * 大图预览。
 *
 * @param images 可加载的图片地址列表, 支持 file:// / http(s):// / data: / r2://(Coil 侧有 R2ImageFetcher)。
 * @param initialPage 初始页码。调用方点了第 n 张就传 n, 不要再自己旋转列表 ——
 *   旋转在"多条记录共用同一个地址"(R2 内容去重上传)时会定位到错误的图。
 */
@Composable
fun ImagePreviewDialog(
    images: List<String>,
    initialPage: Int = 0,
    bottomActions: (@Composable RowScope.() -> Unit)? = null,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val r2MediaStore: R2MediaStore = koinInject()
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val startPage = initialPage.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    // rememberZoomablePagerState 内部是无 key 的 remember, initialPage 变化不会重建;
    // 用 key() 强制换 identity, 保证"下一次打开另一张"能落到正确页码。
    key(startPage, images.size) {
        val state = rememberZoomablePagerState(initialPage = startPage) { images.size }
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Box {
                ImagePager(
                    modifier = Modifier.fillMaxSize(),
                    pagerState = state,
                    imageLoader = { index ->
                        val painter = rememberAsyncImagePainter(images[index])
                        return@ImagePager Pair(painter, painter.intrinsicSize)
                    },
                )

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(1f)
                        .padding(8.dp),
                ) {
                    Icon(HugeIcons.Cancel01, null, tint = Color.White)
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(1f)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (bottomActions != null) {
                        bottomActions()
                    } else {
                        IconButton(
                            onClick = {
                                lifecycleOwner.lifecycleScope.launch {
                                    runCatching {
                                        toaster.show("正在保存")
                                        val imgUrl = images[state.currentPage]
                                        // FilesManager 不认 r2://(私有桶), 先现签成 https 再走保存。
                                        val saveUrl = if (R2Ref.parse(imgUrl) != null) {
                                            r2MediaStore.displayUrl(imgUrl)
                                        } else imgUrl
                                        filesManager.saveMessageImage(context, saveUrl)
                                        toaster.show(message = "已保存图片", type = ToastType.Success)
                                    }.onFailure {
                                        it.printStackTrace()
                                        toaster.show(
                                            message = it.toString(),
                                            type = ToastType.Error
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(HugeIcons.Download01, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
