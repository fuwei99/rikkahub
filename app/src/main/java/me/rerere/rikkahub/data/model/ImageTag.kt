package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.files.FileFolders
import kotlin.uuid.Uuid

/**
 * 相册标签。
 *
 * 与 [Tag]（助手标签）分开建模，因为多了两个助手用不到的维度：
 * - [scope]：标签的作用域。全局标签在所有分类里都能用；folder 标签只在指定分类下出现。
 * - [sensitive]：敏感标记。带此标记的图会从「全部」里隐去并默认打码。
 *
 * OCR 只能从这张表里挑标签，不允许自创 —— 否则模型每次都能发明新词，
 * 标签集会迅速膨胀成噪音，筛选器也就废了。
 */
@Serializable
data class ImageTag(
    val id: Uuid,
    val name: String,
    /** null = 全局；否则为限定的 folder 名（见 FileFolders） */
    val scope: String? = null,
    /** 敏感标签：命中即从「全部」排除 + 默认模糊 */
    val sensitive: Boolean = false,
    /** 内置标签不允许改名/删除，只能改作用域 */
    val builtin: Boolean = false,
) {
    companion object {
        /**
         * 内置 NSFW 标签。id 固定写死，不能随机生成 —— 多端同步时两台设备
         * 各自生成一个 NSFW 会变成两个互不相认的标签，图上的引用也就对不上了。
         */
        val NSFW_ID: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000001")

        val NSFW = ImageTag(
            id = NSFW_ID,
            name = "NSFW",
            scope = null,
            sensitive = true,
            builtin = true,
        )

        /** 保证内置标签始终在列表里（用户删不掉它，只能改作用域） */
        fun withBuiltins(tags: List<ImageTag>): List<ImageTag> =
            if (tags.any { it.id == NSFW_ID }) tags else listOf(NSFW) + tags

        /**
         * 新装默认种子标签。id 全部写死（同 NSFW 的道理）：
         * 多端同步时两台设备各自随机生成一份，会变成互不相认的重复标签。
         *
         * 命名规则：00000000-0000-4000-8000-0000000000XX，XX 从 02 递增。
         * 分组：通用标签全局可见；AI 风格标签限定在 FileFolders.IMAGES（AI 绘图）分类，
         * 避免在其他分类的筛选器里出现与内容无关的风格词。
         */
        val SEED_TAGS: List<ImageTag> = buildList {
            fun tag(idSuffix: String, name: String, scope: String? = null) {
                add(
                    ImageTag(
                        id = Uuid.parse("00000000-0000-4000-8000-0000000000$idSuffix"),
                        name = name,
                        scope = scope,
                    )
                )
            }

            add(NSFW)
            // ---- 通用（全局） ----
            tag("02", "截图")
            tag("03", "表情包")
            tag("04", "文档")
            tag("05", "二维码")
            tag("06", "票据")
            tag("07", "证件")
            tag("08", "人像")
            tag("09", "自拍")
            tag("0A", "动物")
            tag("0B", "风景")
            tag("0C", "食物")
            tag("0D", "建筑")
            tag("0E", "夜景")
            tag("0F", "植物")
            tag("10", "头像")
            // ---- AI 绘图分类专属 ----
            tag("11", "AI 绘图", scope = FileFolders.IMAGES)
            tag("12", "素描", scope = FileFolders.IMAGES)
            tag("13", "古画", scope = FileFolders.IMAGES)
            tag("14", "漫画", scope = FileFolders.IMAGES)
            tag("15", "油彩", scope = FileFolders.IMAGES)
            tag("16", "插画", scope = FileFolders.IMAGES)
            tag("17", "动漫", scope = FileFolders.IMAGES)
            tag("18", "历史画", scope = FileFolders.IMAGES)
            tag("19", "国风", scope = FileFolders.IMAGES)
            tag("1A", "男性", scope = FileFolders.IMAGES)
            tag("1B", "女性", scope = FileFolders.IMAGES)
            tag("1C", "写实", scope = FileFolders.IMAGES)
            tag("1D", "照片", scope = FileFolders.IMAGES)
        }
    }
}
