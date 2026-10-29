package com.heihun.pixivplus.core.config

class ConfigKey<T>(
    val name: String,
    val defaultValue: T,
    val encode: (T) -> String,
    val decode: (String) -> T
)

object ConfigKeys {

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

    val WEBDAV_HOST = ConfigKey("webdav_host", "", { it }, { it })

    val WEBDAV_PORT = ConfigKey("webdav_port", "80", { it }, { it })

    val WEBDAV_HTTPS = ConfigKey("webdav_https", false, { it.toString() }, { it.toBoolean() })

    /** WebDAV 备份目录，默认 /PixivPlus/；可自定义为 /backup/pixiv 等。 */
    val WEBDAV_DIRECTORY = ConfigKey("webdav_directory", "/PixivPlus/", { it }, { it })

    val WEBDAV_USERNAME = ConfigKey("webdav_username", "", { it }, { it })

    val WEBDAV_PASSWORD = ConfigKey("webdav_password", "", { it }, { it })

    /** 本地备份文件夹的 SAF tree URI，仅保留在当前设备。 */
    val BACKUP_FOLDER_URI = ConfigKey("backup_folder_uri", "", { it }, { it })

    /** 不写入备份文件的敏感 / 设备相关配置；恢复时保留本机现有值。 */
    val LOCAL_ONLY_NAMES = setOf(
        PROXY_PASSWORD.name,
        WEBDAV_PASSWORD.name,
        BACKUP_FOLDER_URI.name
    )

    val FILTER_KEYWORDS = ConfigKey("filter_keywords", "", { it }, { it })

    val FILTER_TAGS = ConfigKey("filter_tags", "", { it }, { it })

    val FILTER_AUTHORS = ConfigKey("filter_authors", "", { it }, { it })

    val FILTER_CASE_SENSITIVE = ConfigKey("filter_case_sensitive", false, { it.toString() }, { it.toBoolean() })

    val HISTORY_RETENTION_DAYS = ConfigKey("history_retention_days", "30", { it }, { it })

    val HISTORY_PAUSED = ConfigKey("history_paused", false, { it.toString() }, { it.toBoolean() })
}
