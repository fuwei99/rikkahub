package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 图像编辑图标（图片 + 画笔）。
 * 相册「编辑图片」专用，与「重命名」(Edit02) 区分开。
 * 源 SVG: viewBox 0 0 64 64, stroke-width 2.5, round cap/join。
 */
val ImageEditIcon: ImageVector
    get() {
        if (_imageEditIcon != null) {
            return _imageEditIcon!!
        }
        _imageEditIcon = ImageVector.Builder(
            name = "ImageEdit",
            defaultWidth = 64.dp,
            defaultHeight = 64.dp,
            viewportWidth = 64f,
            viewportHeight = 64f,
        ).apply {
            // 画框（右侧开口留给画笔）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(33f, 53f)
                lineTo(15f, 53f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx = -3f, dy = -3f)
                lineTo(12f, 15f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx = 3f, dy = -3f)
                lineTo(49f, 12f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx = 3f, dy = 3f)
                lineTo(52f, 31f)
            }
            // 图中的圆（太阳）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(24f, 20f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx = 0f, dy = 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx = 0f, dy = -8f)
            }
            // 图中的山
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 43f)
                lineTo(21.5f, 33.5f)
                lineTo(27.5f, 39.5f)
                lineTo(36.5f, 29.5f)
                lineTo(41.5f, 34.5f)
            }
            // 画笔轮廓
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(49.5f, 30.5f)
                lineTo(54f, 35f)
                lineTo(39.5f, 49.5f)
                lineTo(33f, 51.5f)
                lineTo(35f, 45f)
                close()
            }
            // 笔尖
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(47f, 33f)
                lineTo(51.5f, 37.5f)
            }
        }.build()
        return _imageEditIcon!!
    }

private var _imageEditIcon: ImageVector? = null
