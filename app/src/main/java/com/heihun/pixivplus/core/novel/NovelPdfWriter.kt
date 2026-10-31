package com.heihun.pixivplus.core.novel

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 把小说块渲染为 A4 PDF。
 *
 * - 文本用 StaticLayout 逐行排版，页底自动换页；
 * - 插图按内容宽度等比缩放，放不下时先换页；
 * - 下载失败的插图绘制占位文字，不中断导出。
 */
object NovelPdfWriter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 52f
    private const val BOTTOM = PAGE_HEIGHT - MARGIN

    private const val TEXT_SIZE = 12f
    private const val HEADING_SIZE = 18f
    private const val CAPTION_SIZE = 9f
    private const val IMAGE_GAP = 10f

    fun write(
        title: String,
        author: String,
        sourceUrl: String,
        blocks: List<NovelBlock>,
        images: Map<String, Bitmap?>,
        labels: Map<NovelBlock, Int>
    ): ByteArray {
        val document = PdfDocument()
        try {
            val textPaint = TextPaint().apply {
                textSize = TEXT_SIZE
                isAntiAlias = true
                color = Color.BLACK
            }
            val headingPaint = TextPaint().apply {
                textSize = HEADING_SIZE
                isAntiAlias = true
                color = Color.BLACK
                isFakeBoldText = true
            }
            val metaPaint = TextPaint().apply {
                textSize = CAPTION_SIZE
                isAntiAlias = true
                color = Color.DKGRAY
            }
            val captionPaint = TextPaint().apply {
                textSize = CAPTION_SIZE
                isAntiAlias = true
                color = Color.GRAY
            }
            val framePaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }

            val contentWidth = (PAGE_WIDTH - 2 * MARGIN).toInt()
            var pageNumber = 1
            var pageInfo = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            )
            var canvas = pageInfo.canvas
            var y = MARGIN

            fun finishPage() {
                val label = pageNumber.toString()
                val width = captionPaint.measureText(label)
                canvas.drawText(label, (PAGE_WIDTH - width) / 2f, PAGE_HEIGHT - 28f, captionPaint)
                document.finishPage(pageInfo)
            }

            fun newPage() {
                finishPage()
                pageNumber++
                pageInfo = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                )
                canvas = pageInfo.canvas
                y = MARGIN
            }

            fun ensure(height: Float) {
                if (y + height > BOTTOM) newPage()
            }

            fun drawLine(text: String, paint: TextPaint, spacingBefore: Float = 0f, spacingAfter: Float = 2f) {
                if (y > MARGIN) y += spacingBefore
                if (text.isEmpty()) {
                    y += paint.fontSpacing + spacingAfter
                    return
                }
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, paint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(1.5f, 1f)
                    .build()
                ensure(layout.height.toFloat() + spacingAfter)
                canvas.save()
                canvas.translate(MARGIN, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + spacingAfter
            }

            fun drawDivider() {
                ensure(12f)
                val mid = y + 6f
                canvas.drawLine(MARGIN, mid, PAGE_WIDTH - MARGIN, mid, framePaint)
                y += 12f
            }

            fun drawImage(bitmap: Bitmap, label: Int) {
                val scale = contentWidth / bitmap.width.toFloat()
                var drawWidth = contentWidth.toFloat()
                var drawHeight = bitmap.height * scale
                val maxImageHeight = BOTTOM - MARGIN - CAPTION_SIZE * 2
                if (drawHeight > maxImageHeight) {
                    drawHeight = maxImageHeight
                    drawWidth = bitmap.width * (drawHeight / bitmap.height)
                }
                ensure(drawHeight + IMAGE_GAP + CAPTION_SIZE + 4f)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(MARGIN, y, MARGIN + drawWidth, y + drawHeight),
                    framePaint
                )
                y += drawHeight + 4f
                drawLine("[插图 $label]", captionPaint)
                y += IMAGE_GAP - 2f
            }

            // 封面头部：标题 / 作者 / 来源。
            drawLine(title, headingPaint, spacingAfter = 8f)
            if (author.isNotBlank()) drawLine("作者：$author", metaPaint, spacingAfter = 2f)
            if (sourceUrl.isNotBlank()) drawLine("来源：$sourceUrl", metaPaint, spacingAfter = 2f)
            drawDivider()

            for (block in blocks) {
                when (block) {
                    is NovelBlock.PageBreak -> {
                        newPage()
                    }
                    is NovelBlock.Chapter -> drawLine(block.title, headingPaint, 10f, 8f)
                    is NovelBlock.Text -> drawLine(block.line, textPaint, 0f, 2f)
                    is NovelBlock.Link -> drawLine("${block.title} (${block.url})", textPaint, 0f, 2f)
                    is NovelBlock.Image -> {
                        val label = labels[block] ?: 1
                        val bitmap = images[NovelMarkers.imageKey(block)]
                        if (bitmap != null) {
                            drawImage(bitmap, label)
                        } else {
                            drawLine("【插图 $label（下载失败）】", captionPaint, 6f, 6f)
                        }
                    }
                    is NovelBlock.Picture -> {
                        val label = labels[block] ?: 1
                        val bitmap = images[NovelMarkers.imageKey(block)]
                        if (bitmap != null) {
                            drawImage(bitmap, label)
                        } else {
                            drawLine("【插图 $label（下载失败）】", captionPaint, 6f, 6f)
                        }
                    }
                }
            }

            finishPage()

            val output = java.io.ByteArrayOutputStream()
            document.writeTo(output)
            return output.toByteArray()
        } finally {
            try {
                document.close()
            } catch (_: Throwable) {
            }
        }
    }
}
