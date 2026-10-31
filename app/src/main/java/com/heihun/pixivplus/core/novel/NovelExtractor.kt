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

    /**
     * 从阅读页 DOM 提取小说正文 token：
     * `x` 文本节点、`i` 图片、`n` 块级换行（<br> 与块元素边界）。
     *
     * 阅读页结构（实测 /webview/v2/novel 服务端渲染）：
     * `<body id="content">` 下有封面 / 标题 / 收藏 / 简介等，正文固定在
     * `<div id="text">` 中，分页为 `<div id="N" class="page">`，每行是
     * `<span id="lineN">` + `<br>`。优先取 `#text`，缺失时退回「高度 + 段落 +
     * 深度」评分定位；选中的容器存到 `window.__plpTarget`，供译文替换 /
     * 还原使用。遍历时跳过隐藏元素、注音节点、按钮与短链接文本。
     */
    const val EXTRACT_SCRIPT = """
(function(){
  var out=[];
  function visible(el){
    try{return el.getClientRects().length>0;}catch(e){return true;}
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
  var root=pickRoot();
  try{window.__plpTarget=root;}catch(e){}
  function walk(node){
    var children=node.childNodes;
    for(var j=0;j<children.length;j++){
      var n=children[j];
      if(n.nodeType===3){
        var t=n.textContent;
        if(t&&t.trim())out.push({t:'x',s:t});
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
        var block=(tag==='P'||tag==='DIV'||tag==='SECTION'||tag==='FIGURE'||tag==='LI'||tag==='BLOCKQUOTE'||(tag.charAt(0)==='H'&&tag.length===2));
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

    /** 把译文写入正文容器（保留原文 HTML，可随时还原）；未提取过时退回整个 body。 */
    fun applyTranslationScript(text: String): String {
        return "(function(){try{" +
            "var el=window.__plpTarget||document.body;" +
            "if(!window.__plpOriginalHtml){window.__plpOriginalHtml=el.innerHTML;}" +
            "var t=" + JSONObject.quote(text) + ";" +
            "el.innerHTML='<div style=\"white-space:pre-wrap;word-break:break-word;" +
            "padding:16px;line-height:1.8;\">'+" +
            "t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')+'</div>';" +
            "return true;}catch(e){return false;}})()"
    }

    /** 还原正文容器原文；没有保存过原文时返回 false。 */
    const val RESTORE_SCRIPT = """
(function(){try{
  var el=window.__plpTarget||document.body;
  if(window.__plpOriginalHtml){
    el.innerHTML=window.__plpOriginalHtml;
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
