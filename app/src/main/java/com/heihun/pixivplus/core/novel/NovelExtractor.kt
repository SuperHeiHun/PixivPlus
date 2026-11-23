package com.heihun.pixivplus.core.novel

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从阅读页 WebView DOM 提取小说内容。
 *
 * 阅读页的正文已经由宿主渲染在 WebView 中，直接读取 DOM 不依赖额外
 * 网络请求，登录限定 / R-18 作品同样可用。提取结果 token：
   * `x` 文本节点、`h` 章节标题文本、`i` 图片、`n` 作者换行、`b` 布局边界、`p` 分页。
 */
object NovelExtractor {

    /**
     * 从阅读页 DOM 提取小说正文 token：
     * `x` 文本节点、`h` 章节标题文本、`i` 图片、`n` 换行（<br> 与块元素边界）、
     * `p` 分页（`<div id="N" class="page">` 页容器）。
     *
     * 阅读页结构（实测 /webview/v2/novel 服务端渲染）：
     * `<body id="content">` 下有封面 / 标题 / 收藏 / 简介等，正文固定在
     * `<div id="text">` 中，分页为 `<div id="N" class="page">`，每行是
     * `<span id="lineN">` + `<br>`。只取已知正文容器 `#text`，缺失时返回空结果，
     * 不按高度猜测或退回 body，避免把评论等整页内容导出；容器存到 `window.__plpTarget`，供译文替换 /
     * 还原使用。遍历时跳过隐藏元素、注音节点与按钮，正文链接文字保留；
     * 标题元素（H1-H6）内的文本以 `h` token 输出用于章节排版；
     * 已显示译文时（存在包装层）改为提取原文包装层，保证可重复翻译。
     */
    const val EXTRACT_SCRIPT = """
(function(){
  var out=[];
  var target=document.getElementById('text');
  if(!target){window.__plpTarget=null;return JSON.stringify(out);}
  var wrap=document.getElementById('plp-original-wrap');
  if(wrap&&wrap.parentNode!==target)wrap=null;
  function visible(el){
    try{
      if(wrap&&wrap.contains(el))return true;
      return el.getClientRects().length>0;
    }catch(e){return true;}
  }
  function excluded(el){
    // 根据节点标识过滤 UI 区域，不按正文文字过滤，避免误删小说里的「评论」等字样。
    var identity=(el.id||'')+' '+(el.className||'');
    return /(^|[\s_-])(comments?|replies|caption|recommendations?|related|ads?|advertisement|bookmark|social)([\s_-]|$)/i.test(identity);
  }
  var root=wrap||target;
  window.__plpTarget=target;
  var hdepth=0;
  var seenPage=false;
  function authoredWhitespace(node){
    for(var el=node.parentNode;el;el=el.parentNode){
      if(/^line\d+$/.test(el.id||''))return true;
      try{if(window.getComputedStyle&&/^(pre|pre-wrap|pre-line|break-spaces)$/.test(window.getComputedStyle(el).whiteSpace))return true;}catch(e){}
      if(el===root)break;
    }
    return false;
  }
  function walk(node){
    var children=node.childNodes;
    for(var j=0;j<children.length;j++){
      var n=children[j];
      if(n.nodeType===3){
        var t=n.textContent;
        if(t){
          var preserve=authoredWhitespace(n);
          // 模板缩进不属于正文；行节点内的空格、全角缩进和显式换行则原样保留。
          if(!preserve&&/^[\t\r\n ]+$/.test(t)&&/[\r\n]/.test(t))continue;
          if(!preserve)t=t.replace(/[\t\r\n ]+/g,' ');
          out.push({t:hdepth>0?'h':'x',s:t});
        }
      }else if(n.nodeType===1){
        var tag=n.tagName;
        if(excluded(n))continue;
        if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='RT'||tag==='RP'||tag==='NAV'||tag==='HEADER'||tag==='FOOTER'||tag==='BUTTON'||tag==='INPUT'||tag==='SELECT'||tag==='OPTION')continue;
        if(!visible(n))continue;
        if(tag==='IMG'){
          var s=n.currentSrc||n.getAttribute('src')||n.getAttribute('data-src')||'';
          if(s)out.push({t:'i',s:s});
          continue;
        }
        if(tag==='BR'){out.push({t:'n'});continue;}
        if(tag.length===2&&tag.charAt(0)==='H'&&tag.charCodeAt(1)>=49&&tag.charCodeAt(1)<=54){
          hdepth++;out.push({t:'b'});walk(n);hdepth--;out.push({t:'b'});continue;
        }
        if(tag==='DIV'&&n.id&&!isNaN(Number(n.id))&&/\bpage\b/.test(n.className||'')){
          if(seenPage)out.push({t:'p'});
          seenPage=true;walk(n);out.push({t:'b'});continue;
        }
        var block=(tag==='P'||tag==='DIV'||tag==='SECTION'||tag==='FIGURE'||tag==='LI'||tag==='BLOCKQUOTE');
        if(block)out.push({t:'b'});
        walk(n);
        if(block)out.push({t:'b'});
      }
    }
  }
  walk(root);
  return JSON.stringify(out);
})()
"""

    /**
     * 译文覆盖层脚本：把原文 DOM 移入隐藏包装层（不销毁，配图与宿主交互保留），
     * 在正文容器内追加译文层；重复调用时复用包装层 / 译文层。入参为译文 HTML。
     */
    fun applyTranslationScript(html: String): String {
        return "(function(html){try{" +
            "var el=window.__plpTarget;" +
            "if(!el||el!==document.getElementById('text'))return false;" +
            "var wrap=document.getElementById('plp-original-wrap');" +
            "if(!wrap||wrap.parentNode!==el){" +
            "if(wrap&&wrap.parentNode)wrap.parentNode.removeChild(wrap);" +
            "wrap=document.createElement('div');" +
            "wrap.id='plp-original-wrap';" +
            "while(el.firstChild)wrap.appendChild(el.firstChild);" +
            "el.appendChild(wrap);" +
            "}" +
            "wrap.style.display='none';" +
            "var t=document.getElementById('plp-translation');" +
            "if(!t||t.parentNode!==el){" +
            "if(t&&t.parentNode)t.parentNode.removeChild(t);" +
            "t=document.createElement('div');" +
            "t.id='plp-translation';" +
            "el.appendChild(t);" +
            "}" +
            "t.setAttribute('style','padding:16px;line-height:1.8;word-break:break-word;');" +
            "t.innerHTML=html;" +
            "try{window.scrollTo(0,0);}catch(e2){}" +
            "return true;}catch(e){return false;}})(" + JSONObject.quote(html) + ")"
    }

    /**
     * 还原正文：移除译文层，把包装层里的原文 DOM 移回原位置并删除包装层。
     * 原文节点从未被销毁，配图加载状态与宿主事件绑定原样保留。
     */
    const val RESTORE_SCRIPT = """
(function(){try{
  var el=window.__plpTarget||document.body;
  var t=document.getElementById('plp-translation');
  var wrap=document.getElementById('plp-original-wrap');
  if(t&&t.parentNode!==el)t=null;
  if(wrap&&wrap.parentNode!==el)wrap=null;
  if(!t&&!wrap)return false;
  if(t&&t.parentNode)t.parentNode.removeChild(t);
  if(wrap){
    while(wrap.firstChild)el.appendChild(wrap.firstChild);
    if(wrap.parentNode)wrap.parentNode.removeChild(wrap);
  }
  try{window.scrollTo(0,0);}catch(e2){}
  return true;
}catch(e){return false;}})()
"""

    /**
     * DOM token → 排版块：文本合并为行，空行保留（排版留白），标题转章节，
     * 分页标记保留（连续分页合并），图片保持原位置顺序。
     */
    fun blocksFromTokens(tokens: JSONArray): List<NovelBlock> {
        val blocks = ArrayList<NovelBlock>()
        val line = StringBuilder()
        var lineIsHeading = false

        // 插图 / 分页等结构 token 自带块边界，会多产生一个换行 token；
        // 吸收紧邻的一个空块（结构噪声），作者额外输入的空行仍然保留。
        var suppressBlank = false

        fun flush() {
            val text = line.toString()
            val heading = lineIsHeading
            line.setLength(0)
            lineIsHeading = false
            if (heading && text.isBlank()) return
            when {
                heading && text.isNotBlank() -> {
                    suppressBlank = false
                    blocks.add(NovelBlock.Chapter(text))
                }
                text.isNotEmpty() -> {
                    suppressBlank = false
                    blocks.add(NovelBlock.Text(text))
                }
                else -> {
                    if (suppressBlank) {
                        suppressBlank = false
                    } else {
                        blocks.add(NovelBlock.Text(""))
                    }
                }
            }
        }

        for (index in 0 until tokens.length()) {
            val token = tokens.optJSONObject(index) ?: continue
            when (val type = token.optString("t")) {
                "x", "h" -> {
                    if (type == "h" && !lineIsHeading && line.isNotEmpty()) flush()
                    if (type == "h") lineIsHeading = true
                    val parts = token.optString("s").replace("\r\n", "\n").replace('\r', '\n').split('\n')
                    for ((partIndex, part) in parts.withIndex()) {
                        if (partIndex > 0) flush()
                        line.append(part)
                    }
                }
                "n" -> flush()
                "b" -> if (line.isNotEmpty() || lineIsHeading) flush()
                "p" -> {
                    if (line.isNotEmpty() || lineIsHeading) flush()
                    if (blocks.lastOrNull() != NovelBlock.PageBreak) blocks.add(NovelBlock.PageBreak)
                    suppressBlank = true
                }
                "i" -> {
                    if (line.isNotEmpty() || lineIsHeading) flush()
                    val url = token.optString("s")
                    if (url.startsWith("http")) {
                        blocks.add(NovelBlock.Picture(url))
                        suppressBlank = true
                    }
                }
            }
        }
        if (line.isNotEmpty() || lineIsHeading) flush()
        // 兼容旧提取器的首尾页容器标记，但不删除作者的首尾空行。
        while (blocks.firstOrNull() is NovelBlock.PageBreak) {
            blocks.removeAt(0)
        }
        while (blocks.lastOrNull() is NovelBlock.PageBreak) {
            blocks.removeAt(blocks.size - 1)
        }
        return blocks
    }
}
