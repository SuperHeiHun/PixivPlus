package com.heihun.pixivplus.core.novel

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从阅读页 WebView DOM 提取小说内容。
 *
 * 阅读页的正文已经由宿主渲染在 WebView 中，直接读取 DOM 不依赖额外
 * 网络请求，登录限定 / R-18 作品同样可用。提取结果 token：
 * `x` 文本节点、`h` 章节标题文本、`i` 图片、`n` 换行、`p` 分页（[newpage]）。
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
     * `<span id="lineN">` + `<br>`。优先取 `#text`，缺失时退回「高度 + 段落 +
     * 深度」评分定位；选中的容器存到 `window.__plpTarget`，供译文替换 /
     * 还原使用。遍历时跳过隐藏元素、注音节点、按钮与短链接文本；
     * 标题元素（H1-H6）内的文本以 `h` token 输出用于章节排版；
     * 已显示译文时（存在包装层）改为提取原文包装层，保证可重复翻译。
     */
    const val EXTRACT_SCRIPT = """
(function(){
  var out=[];
  var wrap=document.getElementById('plp-original-wrap');
  function visible(el){
    try{
      if(wrap&&wrap.contains(el))return true;
      return el.getClientRects().length>0;
    }catch(e){return true;}
  }
  function pickRoot(){
    try{
      var t=document.getElementById('text');
      if(t)return t;
    }catch(e){}
    var body=document.body;
    var bodyLen=(body.textContent||'').length||1;
    var best=null,bestScore=-1;
    try{
      var els=body.querySelectorAll('div,section,article,main');
      for(var i=0;i<els.length;i++){
        var el=els[i];
        if(!visible(el))continue;
        var t=el.textContent||'';
        if(t.length<Math.max(200,bodyLen*0.4))continue;
        var rect=el.getBoundingClientRect();
        if(rect.height<100)continue;
        var depth=0,n=el;
        while(n&&n!==body){n=n.parentElement;depth++;}
        var pCount=el.querySelectorAll('p').length;
        var score=rect.height*2+pCount*800+depth*50;
        if(score>bestScore){bestScore=score;best=el;}
      }
    }catch(e){}
    return best||body;
  }
  var root=wrap||pickRoot();
  try{window.__plpTarget=wrap&&wrap.parentNode?wrap.parentNode:root;}catch(e){}
  var hdepth=0;
  function walk(node){
    var children=node.childNodes;
    for(var j=0;j<children.length;j++){
      var n=children[j];
      if(n.nodeType===3){
        var t=n.textContent;
        if(t&&t.trim())out.push({t:hdepth>0?'h':'x',s:t});
      }else if(n.nodeType===1){
        var tag=n.tagName;
        if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='RT'||tag==='RP'||tag==='NAV'||tag==='HEADER'||tag==='FOOTER'||tag==='BUTTON'||tag==='INPUT'||tag==='SELECT'||tag==='OPTION')continue;
        if(!visible(n))continue;
        if(tag==='A'&&(n.textContent||'').trim().length<=30)continue;
        if(tag==='IMG'){
          var s=n.currentSrc||n.getAttribute('src')||n.getAttribute('data-src')||'';
          if(s)out.push({t:'i',s:s});
          continue;
        }
        if(tag==='BR'){out.push({t:'n'});continue;}
        if(tag.length===2&&tag.charAt(0)==='H'&&tag.charCodeAt(1)>=49&&tag.charCodeAt(1)<=54){
          hdepth++;out.push({t:'n'});walk(n);hdepth--;out.push({t:'n'});continue;
        }
        if(tag==='DIV'&&n.id&&!isNaN(Number(n.id))&&/\bpage\b/.test(n.className||'')){
          out.push({t:'p'});walk(n);continue;
        }
        var block=(tag==='P'||tag==='DIV'||tag==='SECTION'||tag==='FIGURE'||tag==='LI'||tag==='BLOCKQUOTE');
        if(block)out.push({t:'n'});
        walk(n);
        if(block)out.push({t:'n'});
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
            "var el=window.__plpTarget||document.body;" +
            "if(!el)return false;" +
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
            val text = line.toString().trim()
            val heading = lineIsHeading
            line.setLength(0)
            lineIsHeading = false
            when {
                heading && text.isNotEmpty() -> {
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

        fun dropTrailingBlanks() {
            while (blocks.isNotEmpty() && blocks.last() == NovelBlock.Text("")) {
                blocks.removeAt(blocks.size - 1)
            }
        }

        for (index in 0 until tokens.length()) {
            val token = tokens.optJSONObject(index) ?: continue
            when (val type = token.optString("t")) {
                "x", "h" -> {
                    if (type == "h" && !lineIsHeading && line.isNotEmpty()) flush()
                    if (type == "h") lineIsHeading = true
                    val parts = token.optString("s").split('\n')
                    for ((partIndex, part) in parts.withIndex()) {
                        if (partIndex > 0) flush()
                        line.append(part)
                    }
                }
                "n" -> flush()
                "p" -> {
                    flush()
                    dropTrailingBlanks()
                    if (blocks.lastOrNull() != NovelBlock.PageBreak) blocks.add(NovelBlock.PageBreak)
                    suppressBlank = true
                }
                "i" -> {
                    flush()
                    dropTrailingBlanks()
                    val url = token.optString("s")
                    if (url.startsWith("http")) {
                        blocks.add(NovelBlock.Picture(url))
                        suppressBlank = true
                    }
                }
            }
        }
        flush()
        // 去掉首尾的空行 / 分页标记。
        while (blocks.firstOrNull() == NovelBlock.Text("") || blocks.firstOrNull() is NovelBlock.PageBreak) {
            blocks.removeAt(0)
        }
        while (blocks.lastOrNull() == NovelBlock.Text("") || blocks.lastOrNull() is NovelBlock.PageBreak) {
            blocks.removeAt(blocks.size - 1)
        }
        return blocks
    }
}
