package com.heihun.pixivplus.core.util

/** 宿主菜单使用默认 order=0；模块项使用独立排序值，不依赖功能轮询的先后顺序。 */
object FeatureMenuOrder {
    const val BLOCK_TAG = 10000
    const val BLOCK_AUTHOR = 10001
    const val BLOCK_TITLE = 10002
    const val TRANSLATE_CAPTION = 10100
    const val TRANSLATE_NOVEL = 10101
    const val RESTORE_NOVEL = 10102
    const val SAVE_ORIGINAL = 10200
    const val SAVE_GIF = 10201
    const val SAVE_PDF = 10210
    const val SAVE_TXT = 10211
}
