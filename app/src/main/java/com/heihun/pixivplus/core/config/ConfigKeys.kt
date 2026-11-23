package com.heihun.pixivplus.core.config

class ConfigKey<T>(
    val name: String,
    val defaultValue: T,
    val encode: (T) -> String,
    val decode: (String) -> T
)

object ConfigKeys {

    /** 仅控制模块界面，不改变宿主配置或翻译目标语言。 */
    val UI_LANGUAGE = ConfigKey("ui_language", "system", { it }, { it })

    val HISTORY_ENABLED = ConfigKey("history_enabled", true, { it.toString() }, { it.toBoolean() })

    val HISTORY_BRIDGE_ENABLED = ConfigKey("history_bridge_enabled", true, { it.toString() }, { it.toBoolean() })

    val FILTER_ENABLED = ConfigKey("filter_enabled", true, { it.toString() }, { it.toBoolean() })

    val PREMIUM_OVERRIDE = ConfigKey("premium_override", true, { it.toString() }, { it.toBoolean() })

    val ADS_REMOVAL = ConfigKey("ads_removal", true, { it.toString() }, { it.toBoolean() })

    val CAPTION_FIX = ConfigKey("caption_fix", true, { it.toString() }, { it.toBoolean() })

    val PROXY_MODE = ConfigKey("proxy_mode", "system", { it }, { it })

    val PROXY_HOST = ConfigKey("proxy_host", "", { it }, { it })

    val PROXY_PORT = ConfigKey("proxy_port", "7890", { it }, { it })

    val PROXY_USERNAME = ConfigKey("proxy_username", "", { it }, { it })

    val PROXY_PASSWORD = ConfigKey("proxy_password", "", { it }, { it })

    /** 让宿主 App 的网络请求也走模块代理。 */
    val APP_PROXY_ENABLED = ConfigKey("app_proxy_enabled", false, { it.toString() }, { it.toBoolean() })

    val WEBDAV_HOST = ConfigKey("webdav_host", "", { it }, { it })

    val WEBDAV_PORT = ConfigKey("webdav_port", "80", { it }, { it })

    val WEBDAV_HTTPS = ConfigKey("webdav_https", false, { it.toString() }, { it.toBoolean() })

    /** WebDAV 备份目录，默认 /PixivPlus/；可自定义为 /backup/pixiv 等。 */
    val WEBDAV_DIRECTORY = ConfigKey("webdav_directory", "/PixivPlus/", { it }, { it })

    val WEBDAV_USERNAME = ConfigKey("webdav_username", "", { it }, { it })

    val WEBDAV_PASSWORD = ConfigKey("webdav_password", "", { it }, { it })

    /** 本地备份文件夹的 SAF tree URI，仅保留在当前设备。 */
    val BACKUP_FOLDER_URI = ConfigKey("backup_folder_uri", "", { it }, { it })

    /** 小说保存（PDF / TXT）总开关。 */
    val NOVEL_SAVE_ENABLED = ConfigKey("novel_save_enabled", true, { it.toString() }, { it.toBoolean() })

    /** 插画原图 / ZIP 与 ugoira GIF 保存。 */
    val ILLUST_SAVE_ENABLED = ConfigKey("illust_save_enabled", true, { it.toString() }, { it.toBoolean() })
    val ILLUST_SAVE_FOLDER_URI = ConfigKey("illust_save_folder_uri", "", { it }, { it })
    val ILLUST_SAVE_INCLUDE_LINKS = ConfigKey("illust_save_include_links", false, { it.toString() }, { it.toBoolean() })
    val ILLUST_SAVE_NAME_TEMPLATE = ConfigKey("illust_save_name_template", "{author}_{title}", { it }, { it })
    val TASK_NOTIFICATIONS_ENABLED = ConfigKey("task_notifications_enabled", true, { it.toString() }, { it.toBoolean() })
    val TASK_LIST_ENABLED = ConfigKey("task_list_enabled", true, { it.toString() }, { it.toBoolean() })
    val TASK_HISTORY_ENABLED = ConfigKey("task_history_enabled", true, { it.toString() }, { it.toBoolean() })

    /** 简介与小说翻译总开关（使用 Google 翻译免费接口，走模块代理设置）。 */
    val TRANSLATE_ENABLED = ConfigKey("translate_enabled", false, { it.toString() }, { it.toBoolean() })

    /** 翻译目标语言代码，如 zh-CN / zh-TW / en / ja / ko。 */
    val TRANSLATE_TARGET = ConfigKey("translate_target", "zh-CN", { it }, { it })

    /** 小说保存位置的 SAF tree URI，首次保存时由用户选择，仅保留在当前设备。 */
    val NOVEL_SAVE_FOLDER_URI = ConfigKey("novel_save_folder_uri", "", { it }, { it })

    /** 侧边栏精简模式：开启后隐藏 drawer_slim_items 列出的菜单项。 */
    val DRAWER_SLIM_ENABLED = ConfigKey("drawer_slim_enabled", false, { it.toString() }, { it.toBoolean() })

    /** 精简模式下隐藏的侧边栏菜单项资源名（drawer_menu_*），逗号分隔。 */
    val DRAWER_SLIM_ITEMS = ConfigKey(
        "drawer_slim_items",
        "drawer_menu_upload,drawer_menu_works,drawer_menu_collection,drawer_menu_novel_marker," +
            "drawer_menu_connections,drawer_menu_following,drawer_menu_follower,drawer_menu_mypixiv",
        { it },
        { it }
    )

    /** 备份内容范围：设置项（除屏蔽规则外的配置）。 */
    val BACKUP_INCLUDE_SETTINGS = ConfigKey("backup_include_settings", true, { it.toString() }, { it.toBoolean() })

    /** 备份内容范围：屏蔽规则（关键词 / 标签 / 作者）。 */
    val BACKUP_INCLUDE_FILTERS = ConfigKey("backup_include_filters", true, { it.toString() }, { it.toBoolean() })

    /** 备份内容范围：浏览历史与作者资料。 */
    val BACKUP_INCLUDE_HISTORY = ConfigKey("backup_include_history", true, { it.toString() }, { it.toBoolean() })

    /** 备份文件加密密码；留空表示不加密。仅保留在本机，不写入备份。 */
    val BACKUP_PASSWORD = ConfigKey("backup_password", "", { it }, { it })

    /** 不写入备份文件的敏感 / 设备相关配置；恢复时保留本机现有值。 */
    val LOCAL_ONLY_NAMES = setOf(
        UI_LANGUAGE.name,
        PROXY_PASSWORD.name,
        WEBDAV_PASSWORD.name,
        BACKUP_FOLDER_URI.name,
        NOVEL_SAVE_FOLDER_URI.name,
        ILLUST_SAVE_FOLDER_URI.name,
        BACKUP_PASSWORD.name
    )

    val FILTER_KEYWORDS = ConfigKey("filter_keywords", "", { it }, { it })

    val FILTER_TAGS = ConfigKey("filter_tags", "", { it }, { it })

    val FILTER_AUTHORS = ConfigKey("filter_authors", "", { it }, { it })

    val FILTER_CASE_SENSITIVE = ConfigKey("filter_case_sensitive", false, { it.toString() }, { it.toBoolean() })

    val HISTORY_RETENTION_DAYS = ConfigKey("history_retention_days", "30", { it }, { it })

    val HISTORY_PAUSED = ConfigKey("history_paused", false, { it.toString() }, { it.toBoolean() })
}
