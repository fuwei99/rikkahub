package me.rerere.rikkahub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.rerere.rikkahub.data.files.FileFolders
import kotlin.uuid.Uuid

/**
 * 相册标签。
 *
 * 与 [Tag]（助手标签）分开建模，因为多了两个助手用不到的维度：
 * - [scopes]：标签作用在哪些分类（见 FileFolders）。多选，全选 = 全局；
 *   空集合 = 未使用（不出现在任何筛选器里，也不进 OCR 白名单）。
 * - [sensitive]：敏感标记。带此标记的图会从「全部」里隐去并默认打码。
 *
 * OCR 只能从这张表里挑标签，不允许自创 —— 否则模型每次都能发明新词，
 * 标签集会迅速膨胀成噪音，筛选器也就废了。
 */
@Serializable(with = ImageTagSerializer::class)
data class ImageTag(
    val id: Uuid,
    val name: String,
    /** 作用域集合：空 = 未使用；非空 = 只在这些分类（FileFolders）的筛选器里出现 */
    val scopes: Set<String> = emptySet(),
    /** 敏感标签：命中即从「全部」排除 + 默认模糊 */
    val sensitive: Boolean = false,
    /** 内置标签不允许改名/删除，只能改作用域 */
    val builtin: Boolean = false,
) {
    companion object {
        /** 全部相册分类。作用域全选这三个 = 全局，不需要单独的「全局」状态 */
        val ALL_SCOPES: Set<String> = setOf(FileFolders.UPLOAD, FileFolders.IMAGES, FileFolders.AVATARS)

        /**
         * 内置 NSFW 标签。id 固定写死，不能随机生成 —— 多端同步时两台设备
         * 各自生成一个 NSFW 会变成两个互不相认的标签，图上的引用也就对不上了。
         */
        val NSFW_ID: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000001")

        val NSFW = ImageTag(
            id = NSFW_ID,
            name = "NSFW",
            scopes = ALL_SCOPES,
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
         * 分组：通用标签全选作用域（= 全局）；AI 风格标签限定在 FileFolders.IMAGES（AI 绘图）分类，
         * 避免在其他分类的筛选器里出现与内容无关的风格词。
         */
        val SEED_TAGS: List<ImageTag> = buildList {
            fun tag(idSuffix: String, name: String, scopes: Set<String> = ALL_SCOPES) {
                add(
                    ImageTag(
                        id = Uuid.parse("00000000-0000-4000-8000-0000000000$idSuffix"),
                        name = name,
                        scopes = scopes,
                    )
                )
            }

            add(NSFW)
            // ---- 通用（全选作用域 = 全局） ----
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
            tag("11", "AI 绘图", scopes = setOf(FileFolders.IMAGES))
            tag("12", "素描", scopes = setOf(FileFolders.IMAGES))
            tag("13", "古画", scopes = setOf(FileFolders.IMAGES))
            tag("14", "漫画", scopes = setOf(FileFolders.IMAGES))
            tag("15", "油彩", scopes = setOf(FileFolders.IMAGES))
            tag("16", "插画", scopes = setOf(FileFolders.IMAGES))
            tag("17", "动漫", scopes = setOf(FileFolders.IMAGES))
            tag("18", "历史画", scopes = setOf(FileFolders.IMAGES))
            tag("19", "国风", scopes = setOf(FileFolders.IMAGES))
            tag("1A", "男性", scopes = setOf(FileFolders.IMAGES))
            tag("1B", "女性", scopes = setOf(FileFolders.IMAGES))
            tag("1C", "写实", scopes = setOf(FileFolders.IMAGES))
            tag("1D", "照片", scopes = setOf(FileFolders.IMAGES))
        }
    }
}

/**
 * [ImageTag] 的序列化器。
 *
 * 老版本 JSON 只有单个 `scope` 字段（null = 全局 / 字符串 = 单个分类），
 * 新版本写 `scopes` 数组。迁移规则：老「全局」→ 全选所有作用域（语义等价），
 * 老单分类 → 单元素集合。
 */
object ImageTagSerializer : KSerializer<ImageTag> {
    private val delegate = ImageTagLegacyDto.serializer()

    override val descriptor: SerialDescriptor get() = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ImageTag) {
        delegate.serialize(
            encoder,
            ImageTagLegacyDto(
                id = value.id,
                name = value.name,
                scopes = value.scopes,
                sensitive = value.sensitive,
                builtin = value.builtin,
            )
        )
    }

    override fun deserialize(decoder: Decoder): ImageTag {
        val dto = delegate.deserialize(decoder)
        return ImageTag(
            id = dto.id,
            name = dto.name,
            // 新数据：scopes 字段存在（可能是空集合 = 未使用）直接采用；
            // 老数据：没有 scopes 字段（null），回退到遗留 scope。
            scopes = dto.scopes ?: when (dto.scope) {
                null -> ALL_SCOPES          // 老「全局」= 全选所有作用域
                else -> setOf(dto.scope)    // 老单分类 = 单元素集合
            },
            sensitive = dto.sensitive,
            builtin = dto.builtin,
        )
    }
}

/**
 * ImageTag 的磁盘/同步格式：新字段 [scopes]（null = 老数据没这个字段）与遗留字段 [scope] 并存。
 * 转换见 [ImageTagSerializer]。
 */
@Serializable
private data class ImageTagLegacyDto(
    val id: Uuid,
    val name: String,
    val scopes: Set<String>? = null,
    val scope: String? = null,
    val sensitive: Boolean = false,
    val builtin: Boolean = false,
)
