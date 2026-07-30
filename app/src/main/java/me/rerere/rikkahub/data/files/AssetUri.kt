package me.rerere.rikkahub.data.files

object AssetUri {
    private const val PREFIX = "asset://managed-files/"

    fun fromId(id: String): String = "$PREFIX$id"

    fun parse(value: String?): String? =
        value?.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.takeIf { it.isNotBlank() && isUuidLike(it) }

    fun isAsset(value: String?): Boolean = parse(value) != null

    private fun isUuidLike(value: String): Boolean =
        value.length == 36 && value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
}
