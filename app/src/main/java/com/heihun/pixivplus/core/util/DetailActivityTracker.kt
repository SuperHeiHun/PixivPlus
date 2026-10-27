package com.heihun.pixivplus.core.util

import android.app.Activity
import java.util.concurrent.atomic.AtomicReference

object DetailActivityTracker {

    private val current = AtomicReference<Activity?>(null)

    fun set(activity: Activity?) {
        current.set(activity)
    }

    fun get(): Activity? = current.get()

    fun clear() {
        current.set(null)
    }
}
