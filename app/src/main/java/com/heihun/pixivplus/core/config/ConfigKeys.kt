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

    val FILTER_KEYWORDS = ConfigKey("filter_keywords", "", { it }, { it })

    val FILTER_TAGS = ConfigKey("filter_tags", "", { it }, { it })

    val FILTER_AUTHORS = ConfigKey("filter_authors", "", { it }, { it })

    val FILTER_CASE_SENSITIVE = ConfigKey("filter_case_sensitive", false, { it.toString() }, { it.toBoolean() })

    val HISTORY_RETENTION_DAYS = ConfigKey("history_retention_days", "30", { it }, { it })

    val HISTORY_PAUSED = ConfigKey("history_paused", false, { it.toString() }, { it.toBoolean() })
}
