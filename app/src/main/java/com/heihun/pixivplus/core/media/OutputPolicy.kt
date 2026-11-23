package com.heihun.pixivplus.core.media

internal object OutputPolicy {
    const val DEFAULT_FOLDER = "pixivplus://downloads"
    const val RELATIVE_ROOT = "Download/PixivPlus/"

    fun isDefault(folder: String): Boolean = folder.isBlank() || folder == DEFAULT_FOLDER

    fun requireName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() })
        return name
    }

    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        requireName(name)
        var candidate = name
        var number = 2
        while (exists(candidate)) candidate = "$name (${number++})"
        return candidate
    }
}
