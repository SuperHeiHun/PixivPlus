package com.heihun.pixivplus.core.media

/** 已完成导出的文档，不保存授权、文件内容或临时缓存路径。 */
data class SavedFile(val uri: String, val name: String, val mime: String) {
    fun isValid(): Boolean = uri.startsWith("content://") && uri.length <= 4096 &&
        uri.none { it.isISOControl() } && name.isNotBlank() && name.length <= 512 &&
        name.none { it.isISOControl() } && mime.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))
}
