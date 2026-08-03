package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
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
    }
}
