package com.heihun.pixivplus.core

import com.heihun.pixivplus.core.config.ConfigManager
import com.heihun.pixivplus.core.db.AuthorProfileDao
import com.heihun.pixivplus.core.db.HistoryDao
import com.heihun.pixivplus.core.db.LocalDatabase

object Runtime {

    @Volatile
    var config: ConfigManager? = null

    @Volatile
    var database: LocalDatabase? = null

    @Volatile
    var databasePath: String? = null

    @Volatile
    var historyDao: HistoryDao? = null

    @Volatile
    var authorProfileDao: AuthorProfileDao? = null

    fun configOrNull(): ConfigManager? = config

    fun databaseOrNull(): LocalDatabase? = database

    fun historyDaoOrNull(): HistoryDao? = historyDao

    fun authorProfileDaoOrNull(): AuthorProfileDao? = authorProfileDao
}
