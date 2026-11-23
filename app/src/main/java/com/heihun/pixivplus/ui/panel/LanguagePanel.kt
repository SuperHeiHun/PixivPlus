package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.widget.LinearLayout
import android.widget.Toast
import com.heihun.pixivplus.core.i18n.I18n
import com.heihun.pixivplus.core.i18n.LanguageCatalog
import com.heihun.pixivplus.core.i18n.LanguagePackException
import com.heihun.pixivplus.core.i18n.LanguageText as L
import com.heihun.pixivplus.core.i18n.PackProblem
import com.heihun.pixivplus.core.i18n.SettingsText as S
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.ui.ActivityResultBridge
import com.heihun.pixivplus.ui.UiKit
import java.io.IOException
import java.util.concurrent.Executors

internal object LanguagePanel {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "plp-language-packs").apply { isDaemon = true }
    }

    fun summary(): String {
        val language = I18n.language()
        return if (I18n.preference() == LanguageCatalog.FOLLOW_HOST) S.FOLLOW_HOST_CURRENT(language.nativeName)
            else language.nativeName
    }

    fun build(activity: Activity, body: LinearLayout) {
        val languages = I18n.languages()
        val card = UiKit.card(activity)
        card.addView(UiKit.selectRow(activity, S.LANGUAGE(), summary()) {
            val tags = listOf(LanguageCatalog.FOLLOW_HOST) + languages.map { it.tag }
            val labels = listOf(S.FOLLOW_HOST()) + languages.map {
                if (it.builtIn) it.nativeName else "${it.nativeName} · ${it.tag}"
            }
            ChoiceDialog.show(activity, S.LANGUAGE(), labels, tags.indexOf(I18n.preference())) { index ->
                tags.getOrNull(index)?.let(I18n::select)
            }
        })
        card.addView(UiKit.filledButtonRow(activity, L.IMPORT()) { importPack(activity) })
        card.addView(UiKit.paragraph(activity, L.HELP()))
        body.addView(card)

        val imports = UiKit.card(activity)
        imports.addView(UiKit.cardSectionTitle(activity, L.PACKS()))
        val imported = languages.filterNot { it.builtIn }
        if (imported.isEmpty()) imports.addView(UiKit.paragraph(activity, L.NO_PACKS()))
        for (language in imported) {
            imports.addView(UiKit.infoRow(activity, language.nativeName, language.tag))
            imports.addView(UiKit.dangerButtonRow(activity, L.REMOVE()) {
                ConfirmDialog.show(activity, L.REMOVE(), L.REMOVE_CONFIRM(language.nativeName), L.REMOVE(), danger = true) {
                    worker.execute {
                        try {
                            val wasSelected = I18n.preference() == language.tag
                            I18n.removePack(activity.applicationContext, language.tag)
                            if (wasSelected) I18n.select(LanguageCatalog.FOLLOW_HOST)
                        } catch (e: Exception) {
                            ModuleLog.w("LanguagePanel", "Could not remove a language pack: ${e.javaClass.simpleName}")
                            activity.runOnUiThread { toast(activity, L.REMOVE_FAILED()) }
                        }
                    }
                }
            })
        }
        body.addView(imports)
    }

    private fun importPack(activity: Activity) {
        ActivityResultBridge.pickLanguagePack(activity) { owner, uri ->
            if (uri == null) return@pickLanguagePack
            worker.execute {
                try {
                    val context = owner.applicationContext
                    val language = context.contentResolver.openInputStream(uri)?.use { I18n.importPack(context, it) }
                        ?: throw IOException("No document stream")
                    owner.runOnUiThread {
                        I18n.select(language.tag)
                        toast(owner, L.IMPORTED(language.nativeName))
                    }
                } catch (e: LanguagePackException) {
                    owner.runOnUiThread { toast(owner, errorMessage(e)) }
                } catch (e: Exception) {
                    ModuleLog.w("LanguagePanel", "Import failed: ${e.javaClass.simpleName}")
                    owner.runOnUiThread { toast(owner, L.IMPORT_FAILED()) }
                }
            }
        }
    }

    private fun errorMessage(error: LanguagePackException): String = when (error.problem) {
        PackProblem.INVALID -> L.INVALID()
        PackProblem.SIZE -> L.SIZE()
        PackProblem.VERSION -> L.VERSION()
        PackProblem.LOCALE -> L.LOCALE()
        PackProblem.NAME -> L.NAME()
        PackProblem.ENTRY -> L.ENTRY(error.key.orEmpty())
        PackProblem.PLACEHOLDER -> L.PLACEHOLDER(error.key.orEmpty())
        PackProblem.EMPTY -> L.EMPTY()
        PackProblem.BUILTIN -> L.BUILTIN()
        PackProblem.LIMIT -> L.LIMIT()
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
