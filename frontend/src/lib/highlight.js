import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import csharp from 'highlight.js/lib/languages/csharp';
import css from 'highlight.js/lib/languages/css';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import markdown from 'highlight.js/lib/languages/markdown';
import plaintext from 'highlight.js/lib/languages/plaintext';
import powershell from 'highlight.js/lib/languages/powershell';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

hljs.registerLanguage('bash', bash);
hljs.registerLanguage('csharp', csharp);
hljs.registerLanguage('css', css);
hljs.registerLanguage('java', java);
hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('json', json);
hljs.registerLanguage('markdown', markdown);
hljs.registerLanguage('plaintext', plaintext);
hljs.registerLanguage('powershell', powershell);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('yaml', yaml);

function highlightLanguage(filePath, language) {
  const extension = String(filePath || '').toLowerCase().split('.').pop();
  const normalized = String(language || '').toLowerCase();
  const aliases = {
    cs: 'csharp',
    csharp: 'csharp',
    java: 'java',
    js: 'javascript',
    jsx: 'javascript',
    javascript: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    typescript: 'typescript',
    xml: 'xml',
    xaml: 'xml',
    html: 'xml',
    css: 'css',
    sql: 'sql',
    json: 'json',
    yaml: 'yaml',
    yml: 'yaml',
    md: 'markdown',
    markdown: 'markdown',
    sh: 'bash',
    bash: 'bash',
    shell: 'bash',
    ps1: 'powershell',
    powershell: 'powershell',
  };
  return aliases[normalized] || aliases[extension] || 'plaintext';
}

function highlightedLineHtml(line, language) {
  const value = line || ' ';
  try {
    if (hljs.getLanguage(language)) {
      return hljs.highlight(value, { language, ignoreIllegals: true }).value;
    }
  } catch {
    // Fall back to escaped plain text below.
  }
  return escapeHtml(value);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

export { highlightLanguage, highlightedLineHtml, escapeHtml };
