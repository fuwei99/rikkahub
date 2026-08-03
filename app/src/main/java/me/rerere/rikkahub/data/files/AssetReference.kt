package me.rerere.rikkahub.data.files

/**
 * 图片 / 附件的「地址」统一解析入口。
 *
 * 历史背景: 生图工具曾用 `assistant-round-<N>-ref-<M>.png` 这种 round tag 作为图片地址。
 * 该地址由上下文推导得出 —— 用户连发多条消息、消息分支重生成、上下文裁剪、以及
 * GenerationHandler 回灌工具图片时额外插入的 role=USER 临时消息, 都会让同一张图算出
 * 不同的轮号。tag 因此天生不稳定, 已于 2026-08 废除(仅保留读取兼容)。
 *
 * 现在唯一的稳定地址是 Asset ID(`managed_files.id`, UUID): 数据库主键, 全局唯一,
 * 跨轮 / 跨分支 / 跨会话 / 跨设备同步均不变。
 */
sealed interface AssetRef {
    /** asset://managed-files/<uuid> 或裸 uuid */
    data class Id(val assetId: String) : AssetRef

    /** 旧的 round tag, 例如 assistant-round-1-ref-1.png。仅用于兼容老会话 */
    data class Legacy(val tag: String) : AssetRef

    /** 可读文件路径, 例如 /workspace/a.png、/mnt/obsidian/b.jpg */
    data class Path(val path: String) : AssetRef

    /** 外部 http(s) 链接 */
    data class Remote(val url: String) : AssetRef
}

object AssetReferences {
    private val UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    /** 旧 tag 形态: (user|assistant)-round-<N>-ref-<M>.<ext> */
    private val LEGACY_TAG_REGEX = Regex(
        "^(?:user|assistant)-round-\\d+-ref-\\d+\\.[A-Za-z0-9]+$",
        RegexOption.IGNORE_CASE,
    )

    /** 严格匹配裸 UUID(36 位, 连字符位置固定), 避免与普通文件名混淆 */
    fun bareUuid(value: String): String? =
        value.trim().takeIf { it.length == 36 && UUID_REGEX.matches(it) }

    /** asset uri 或裸 uuid → asset id */
    fun assetId(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        return AssetUri.parse(raw) ?: bareUuid(raw)
    }

    fun isLegacyTag(value: String): Boolean = LEGACY_TAG_REGEX.matches(value.trim())

    /**
     * 判定优先级固定: asset uri > 裸 uuid > http(s) > legacy tag > 文件路径。
     * 顺序不可调整, 否则同一字符串在不同调用点会被解释成不同东西。
     */
    fun classify(raw: String): AssetRef? {
        val value = raw.trim().trim('<', '>').removeSurrounding("\"").removeSurrounding("'")
        if (value.isEmpty()) return null

        assetId(value)?.let { return AssetRef.Id(it) }

        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return AssetRef.Remote(value)
        }

        if (isLegacyTag(value)) return AssetRef.Legacy(value)

        return AssetRef.Path(value)
    }
}
