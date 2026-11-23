// Run the actual Kotlin extraction script against minimal DOM fixtures; no dependencies.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname,
  '../app/src/main/java/com/heihun/pixivplus/core/novel/NovelExtractor.kt'), 'utf8');
const script = source.match(/const val EXTRACT_SCRIPT = """([\s\S]*?)"""/)[1];

function element(tag, id = '', children = [], className = '') {
  const node = {
    nodeType: 1, tagName: tag, id, className, childNodes: children,
    getClientRects: () => [1], getAttribute: () => null,
    contains(other) { return this === other || children.some(child => child.contains?.(other)); },
    get textContent() { return children.map(child => child.textContent).join(''); }
  };
  children.forEach(child => { child.parentNode = node; });
  return node;
}
const text = value => ({ nodeType: 3, textContent: value });
const line = value => element('SPAN', 'line1', [text(value)]);
const br = () => element('BR');

function extract(body) {
  function find(node, id) {
    if (node.id === id) return node;
    for (const child of node.childNodes || []) {
      const found = find(child, id);
      if (found) return found;
    }
    return null;
  }
  const window = {};
  const tokens = JSON.parse(vm.runInNewContext(script, {
    window, document: { body, getElementById: id => find(body, id) }
  }));
  return { tokens, window };
}

// Siblings outside the novel must never be extracted.
const novel = element('DIV', 'text', [line('正文含有评论二字'), br()]);
let result = extract(element('BODY', 'content', [
  element('DIV', 'caption', [text('简介')]), novel,
  element('DIV', 'comments', [text('评论区')]),
  element('DIV', 'related', [text('推荐作品')])
]));
assert.deepEqual(result.tokens.filter(t => t.t === 'x').map(t => t.s), ['正文含有评论二字']);
assert.equal(result.window.__plpTarget, novel);

// Unknown/loading layouts fail closed instead of exporting the whole body.
assert.deepEqual(extract(element('BODY', '', [line('只有评论或其他页面内容')])).tokens, []);

// UI subtrees inside #text are excluded by identifiers, not their text.
result = extract(element('BODY', '', [element('DIV', 'text', [
  line('正文'), element('DIV', 'novel-comments', [text('评论区')]),
  element('DIV', '', [text('广告')], 'ad-container'),
  element('DIV', '', [text('回复')], 'replies')
])]));
assert.deepEqual(result.tokens.filter(t => t.t === 'x').map(t => t.s), ['正文']);

// Saving after translation reads original nodes, never translated overlay text.
const original = element('DIV', 'plp-original-wrap', [line('原文'), br()]);
result = extract(element('BODY', '', [element('DIV', 'text', [
  original, element('DIV', 'plp-translation', [text('译文')])
])]));
assert.deepEqual(result.tokens.filter(t => t.t === 'x').map(t => t.s), ['原文']);

// Chapters, pages and embedded pictures remain part of the novel.
const image = element('IMG');
image.currentSrc = 'https://example.com/novel.jpg';
result = extract(element('BODY', '', [element('DIV', 'text', [
   element('DIV', '1', [element('H2', '', [text('章节')]), line('正文'), br(), image], 'page'),
   element('DIV', '2', [line('次页正文'), br()], 'page')
])]));
assert.ok(result.tokens.some(t => t.t === 'p'));
assert.ok(result.tokens.some(t => t.t === 'h' && t.s === '章节'));
assert.ok(result.tokens.some(t => t.t === 'i' && t.s === image.currentSrc));
// Nested layout wrappers introduce soft boundaries, not authored blank lines.
result = extract(element('BODY', '', [element('DIV', 'text', [
  element('DIV', '', [element('P', '', [text('第一段')]), element('P', '', [text('第二段')])])
])]));
assert.equal(result.tokens.filter(t => t.t === 'n').length, 0);
assert.deepEqual(result.tokens.filter(t => t.t === 'x').map(t => t.s), ['第一段', '第二段']);

// Preserve ideographic/ASCII indentation, spaces between inline spans, and short links.
result = extract(element('BODY', '', [element('DIV', 'text', [
  line('　　缩进  '), text(' '), element('A', '', [text('短链接正文')]), br(), line('  '), br()
])]));
assert.deepEqual(result.tokens.filter(t => t.t === 'x').map(t => t.s), ['　　缩进  ', ' ', '短链接正文', '  ']);
assert.equal(result.tokens.filter(t => t.t === 'n').length, 2);

// Formatting newlines between page containers are not novel text.
result = extract(element('BODY', '', [element('DIV', 'text', [text('\n  '),
  element('DIV', '1', [line('正文'), br()], 'page'), text('\n')
])]));
assert.deepEqual(result.tokens.filter(t => t.t === 'x').map(t => t.s), ['正文']);
assert.equal(result.tokens.filter(t => t.t === 'p').length, 0);
console.log('NovelExtractor DOM fixtures: 8 passed');
