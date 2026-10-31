package com.heihun.pixivplus.core.novel

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从阅读页 WebView DOM 提取小说内容。
 *
 * 阅读页的正文已经由宿主渲染在 WebView 中，直接读取 DOM 不依赖额外
 * 网络请求，登录限定 / R-18 作品同样可用。提取结果 token：
 * `x` 文本节点、`i` 图片、`n` 块级换行（<br> 与块元素边界）。
 */
object NovelExtractor {

    /** 遍历整个 body，跳过隐藏元素（display:none）与注音 / 导航节点。 */
    const val EXTRACT_SCRIPT = """
(function(){
  var out=[];
  function visible(el){
    try{return el.getClientRects().length>0;}catch(e){return true;}
  }
  function walk(node){
    var children=node.childNodes;
    for(var j=0;j<children.length;j++){
      var n=children[j];
      if(n.nodeType===3){
        var t=n.textContent;
        if(t&&t.trim())out.push({t:'x',s:t});
      }else if(n.nodeType===1){
        var tag=n.tagName;
        if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='RT'||tag==='RP'||tag==='NAV'||tag==='HEADER'||tag==='FOOTER')continue;
        if(!visible(n))continue;
        if(tag==='IMG'){
          var s=n.currentSrc||n.getAttribute('src')||n.getAttribute('data-src')||'';
          if(s)out.push({t:'i',s:s});
          continue;
        }
        if(tag==='BR'){out.push({t:'n'});continue;}
        var block=(tag==='P'||tag==='DIV'||tag==='SECTION'||tag==='FIGURE'||tag==='LI'||tag==='BLOCKQUOTE'||(tag.charAt(0)==='H'&&tag.length===2));
        if(block)out.push({t:'n'});
        walk(n);
        if(block)out.push({t:'n'});
      }
    }
  }
  walk(document.body);
  return JSON.stringify(out);
})()
"""

    /** 把译文写入阅读页（保留原文 HTML，可随时还原）。 */
    fun applyTranslationScript(text: String): String {
        return "(function(){try{" +
            "if(!window.__plpOriginalHtml){window.__plpOriginalHtml=document.body.innerHTML;}" +
            "var t=" + JSONObject.quote(text) + ";" +
            "document.body.innerHTML='<div style=\"white-space:pre-wrap;word-break:break-word;" +
            "padding:16px;line-height:1.8;\">'+" +
            "t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')+'</div>';" +
            "return true;}catch(e){return false;}})()"
    }

    /** 还原阅读页原文；没有保存过原文时返回 false。 */
    const val RESTORE_SCRIPT = """
(function(){try{
  if(window.__plpOriginalHtml){
    document.body.innerHTML=window.__plpOriginalHtml;
    window.__plpOriginalHtml=null;
    return true;
  }
  return false;
}catch(e){return false;}})()
"""

    /** DOM token → 排版块：文本合并为行，图片保持原位置顺序。 */
    fun blocksFromTokens(tokens: JSONArray): List<NovelBlock> {
        val blocks = ArrayList<NovelBlock>()
        val line = StringBuilder()
        fun flush() {
            val text = line.toString().trim()
            line.setLength(0)
            if (text.isNotEmpty()) blocks.add(NovelBlock.Text(text))
        }
        for (index in 0 until tokens.length()) {
            val token = tokens.optJSONObject(index) ?: continue
            when (token.optString("t")) {
                "x" -> {
                    val parts = token.optString("s").split('\n')
                    for ((partIndex, part) in parts.withIndex()) {
                        if (partIndex > 0) flush()
                        line.append(part)
                    }
                }
                "n" -> flush()
                "i" -> {
                    flush()
                    val url = token.optString("s")
                    if (url.startsWith("http")) blocks.add(NovelBlock.Picture(url))
                }
            }
        }
        flush()
        return blocks
    }

    /** 排版块 → 纯文本（用于翻译输入）：图片转占位符，章节标题独立成行。 */
    fun toPlainText(blocks: List<NovelBlock>): String {
        val builder = StringBuilder()
        fun newline() {
            if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
        }
        for (block in blocks) {
            when (block) {
                is NovelBlock.PageBreak -> builder.append("\n\n")
                is NovelBlock.Chapter -> {
                    newline()
                    builder.append(block.title).append('\n')
                }
                is NovelBlock.Text -> builder.append(block.line).append('\n')
                is NovelBlock.Link -> builder.append(block.title).append(" (").append(block.url).append(")\n")
                is NovelBlock.Image, is NovelBlock.Picture -> builder.append("[插图]\n")
            }
        }
        return builder.toString().trim()
    }
}
