package com.heihun.pixivplus.ui.panel

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.heihun.pixivplus.core.media.MediaModels
import com.heihun.pixivplus.core.media.MediaPreviewLoader
import com.heihun.pixivplus.core.media.OriginalImage
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.ui.UiKit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 回收式缩略图网格，最多三路预览加载；只加载可见图片，关闭即取消排队任务。 */
object ImageSelectionDialog {
    private class Cell(val root: LinearLayout, val image: ImageView, val status: TextView, val check: CheckBox, val label: TextView)

    fun show(activity: Activity, images: List<OriginalImage>, previewUrl: (OriginalImage) -> String, onSelect: (List<Int>) -> Unit) {
        val dialog = UiKit.dialog(activity)
        val palette = UiKit.colors(activity)
        val root = UiKit.dialogSurface(activity)
        val selected = sortedSetOf<Int>()
        val pending = HashSet<Int>()
        val failed = HashSet<Int>()
        var closed = false
        val previews = object : LruCache<Int, Bitmap>(6 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
        }
        MediaPreviewLoader.register(previews)
        val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(24),
            { task -> Thread(task, "plp-image-preview").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        root.addView(UiKit.dialogHeader(activity, "自定义保存图片", null) { UiKit.dismissAnimated(dialog, root) })
        root.addView(UiKit.divider(activity))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 8), UiKit.dp(activity, 16), 0)
        }
        val summary = TextView(activity).apply {
            text = "已选择 0 / ${images.size} 张 · 点选图片或输入页码"
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
        }
        content.addView(summary)
        var adapter: BaseAdapter? = null
        val field = UiKit.inputField(activity, "", "页码，例如 1,3,5-8", onChanged = { value ->
            try {
                val pages = if (value.isBlank()) emptyList() else MediaModels.selectPages(value, images.size)
                selected.clear(); selected.addAll(pages)
                summary.text = "已选择 ${selected.size} / ${images.size} 张 · 页码从 1 开始"
                adapter?.notifyDataSetChanged()
            } catch (_: IllegalArgumentException) { summary.text = "页码尚未完成，例：1,3,5-8" }
        })
        content.addView(field)
        val tools = LinearLayout(activity)
        tools.addView(UiKit.textButton(activity, "全选").apply { setOnClickListener { field.setText("1-${images.size}") } })
        tools.addView(UiKit.textButton(activity, "清空").apply { setOnClickListener { field.setText("") } })
        content.addView(tools)
        val grid = GridView(activity).apply {
            numColumns = 3
            horizontalSpacing = UiKit.dp(activity, 6)
            verticalSpacing = UiKit.dp(activity, 6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = images.size
            override fun getItem(position: Int) = images[position]
            override fun getItemId(position: Int) = images[position].page.toLong()
            override fun hasStableIds() = true
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = images[position]
                val cell = (convertView?.tag as? Cell) ?: run {
                    val container = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(UiKit.dp(activity, 4), UiKit.dp(activity, 4), UiKit.dp(activity, 4), UiKit.dp(activity, 4))
                    }
                    val preview = FrameLayout(activity)
                    val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    preview.addView(image, FrameLayout.LayoutParams(-1, -1))
                    val status = TextView(activity).apply {
                        gravity = Gravity.CENTER
                        textSize = 12f
                        setTextColor(palette.onSurfaceVariant)
                    }
                    preview.addView(status, FrameLayout.LayoutParams(-1, -1))
                    val checkbox = CheckBox(activity).apply { isClickable = false; isFocusable = false }
                    preview.addView(checkbox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
                    container.addView(preview, LinearLayout.LayoutParams(-1, UiKit.dp(activity, 120)))
                    val label = TextView(activity).apply { gravity = Gravity.CENTER; textSize = 12f; setTextColor(palette.onSurface) }
                    container.addView(label)
                    Cell(container, image, status, checkbox, label).also { container.tag = it }
                }
                val checked = item.page in selected
                cell.check.isChecked = checked
                cell.label.text = "第 ${item.page} 页"
                cell.root.background = GradientDrawable().apply {
                    cornerRadius = UiKit.dp(activity, 8).toFloat()
                    setColor(palette.surfaceContainer)
                    setStroke(UiKit.dp(activity, if (checked) 2 else 1), if (checked) palette.primary else palette.outlineVariant)
                }
                val bitmap = previews.get(item.page)
                cell.image.setImageBitmap(bitmap)
                cell.status.visibility = if (bitmap == null) View.VISIBLE else View.GONE
                cell.status.text = if (item.page in failed) "预览加载失败\n仍可选择此页" else "加载预览…"
                cell.root.setOnClickListener {
                    if (!selected.add(item.page)) selected.remove(item.page)
                    field.setText(selected.joinToString(","))
                }
                if (bitmap == null && item.page !in failed && !closed && pending.add(item.page)) {
                    try {
                        workers.execute {
                            val loaded = try { MediaPreviewLoader.load(ConfigStoreRegistry.store, previewUrl(item), 220) }
                                catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                            activity.runOnUiThread {
                                pending.remove(item.page)
                                if (closed) loaded?.recycle()
                                else {
                                    if (loaded == null) failed.add(item.page) else previews.put(item.page, loaded)
                                    notifyDataSetChanged()
                                }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { pending.remove(item.page) }
                }
                return cell.root
            }
        }
        grid.adapter = adapter
        val maxHeight = minOf(UiKit.dp(activity, 360), (activity.resources.displayMetrics.heightPixels * 0.4f).toInt())
        content.addView(grid, LinearLayout.LayoutParams(-1, maxHeight))
        root.addView(content)
        val footer = LinearLayout(activity).apply {
            gravity = Gravity.END
            setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 8), UiKit.dp(activity, 16), UiKit.dp(activity, 12))
        }
        footer.addView(UiKit.textButton(activity, "取消").apply {
            setOnClickListener { UiKit.dismissAnimated(dialog, root) }
        }, LinearLayout.LayoutParams(-2, UiKit.dp(activity, 40)))
        footer.addView(UiKit.filledButton(activity, "下一步").apply {
            setOnClickListener {
                try {
                    val pages = MediaModels.selectPages(field.text.toString(), images.size)
                    UiKit.dismissAnimated(dialog, root) { onSelect(pages) }
                } catch (t: IllegalArgumentException) { field.error = t.message }
            }
        }, LinearLayout.LayoutParams(-2, UiKit.dp(activity, 40)))
        root.addView(footer)
        dialog.setOnDismissListener {
            closed = true
            workers.shutdownNow()
            pending.clear()
            previews.evictAll()
        }
        dialog.setContentView(root)
        dialog.show()
        UiKit.applyDialogWidth(dialog, 0.92f, 520)
        UiKit.animateDialogIn(root)
    }
}
