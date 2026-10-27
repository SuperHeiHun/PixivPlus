package com.heihun.pixivplus.entry

import com.heihun.pixivplus.core.Host

object PackageGuard {

    fun isHost(packageName: String?): Boolean = packageName == Host.PACKAGE
}
