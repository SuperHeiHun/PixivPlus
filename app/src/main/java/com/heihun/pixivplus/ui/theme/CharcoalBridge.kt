package com.heihun.pixivplus.ui.theme

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog

object CharcoalBridge {

    private const val CHARCOAL_BUTTON = "net.pixiv.charcoal.android.view.button.CharcoalButton"
    private const val CHARCOAL_SWITCH = "net.pixiv.charcoal.android.view.charcoalSwitch.CharcoalSwitch"

    fun isAvailable(context: Context): Boolean {
        return try {
            Class.forName(CHARCOAL_BUTTON, false, context.classLoader) != null
        } catch (_: Throwable) {
            false
        }
    }

    fun newButton(context: Context, text: String): View {
        val charcoal = instantiate(context, CHARCOAL_BUTTON)
        if (charcoal is TextView) {
            charcoal.text = text
            return charcoal
        }
        return Button(context).apply { this.text = text }
    }

    fun newSwitch(context: Context): View {
        val charcoal = instantiate(context, CHARCOAL_SWITCH)
        if (charcoal is android.widget.CompoundButton) return charcoal
        return android.widget.Switch(context)
    }

    fun brandColor(context: Context): Int {
        val resolved = resolveAttribute(context, "colorCharcoalBrand")
        if (resolved != 0) return resolved
        val accent = resolveAttribute(context, null, android.R.attr.colorAccent)
        return if (accent != 0) accent else 0xFF0096FA.toInt()
    }

    fun dividerColor(context: Context): Int {
        val resolved = resolveAttribute(context, "colorCharcoalDivider")
        return if (resolved != 0) resolved else 0x1F000000
    }

    private fun resolveAttribute(context: Context, name: String?, frameworkAttr: Int = 0): Int {
        return try {
            val attrId = if (frameworkAttr != 0) {
                frameworkAttr
            } else {
                val id = context.resources.getIdentifier(name, "attr", Host.PACKAGE)
                if (id == 0) return 0
                id
            }
            val typedValue = TypedValue()
            if (context.theme.resolveAttribute(attrId, typedValue, true)) typedValue.data else 0
        } catch (t: Throwable) {
            ModuleLog.w("CharcoalBridge", "attribute resolve failed", t)
            0
        }
    }

    private fun instantiate(context: Context, className: String): View? {
        return try {
            val clazz = Class.forName(className, true, context.classLoader)
            val constructors = clazz.declaredConstructors
            for (constructor in constructors) {
                val types = constructor.parameterTypes
                if (types.size == 1 && types[0] == Context::class.java) {
                    constructor.isAccessible = true
                    return constructor.newInstance(context) as? View
                }
            }
            for (constructor in constructors) {
                val types = constructor.parameterTypes
                if (types.size == 2 && types[0] == Context::class.java && types[1] == android.util.AttributeSet::class.java) {
                    constructor.isAccessible = true
                    return constructor.newInstance(context, null) as? View
                }
            }
            null
        } catch (t: Throwable) {
            ModuleLog.w("CharcoalBridge", "instantiate $className failed", t)
            null
        }
    }
}
