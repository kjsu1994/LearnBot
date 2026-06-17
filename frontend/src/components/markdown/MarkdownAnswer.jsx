import { Fragment } from 'react';

function MarkdownAnswer({ text = '' }) {
  const blocks = parseMarkdownBlocks(text);
  return (
    <div className="markdown-answer">
      {blocks.map((block, index) => renderMarkdownBlock(block, index))}
    </div>
  );
}

function parseMarkdownBlocks(text = '') {
  const lines = String(text || '').replace(/\r\n/g, '\n').split('\n');
  const blocks = [];
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (!line.trim()) {
      continue;
    }
    if (line.trim().startsWith('```')) {
      const language = line.trim().slice(3).trim();
      const codeLines = [];
      index++;
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        codeLines.push(lines[index]);
        index++;
      }
      blocks.push({ type: 'code', language, text: codeLines.join('\n') });
      continue;
    }
    const heading = line.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1].length, text: heading[2].trim() });
      continue;
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items = [];
      while (index < lines.length && /^\s*[-*]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*[-*]\s+/, '').trim());
        index++;
      }
      index--;
      blocks.push({ type: 'ul', items });
      continue;
    }
    if (/^\s*\d+\.\s+/.test(line)) {
      const items = [];
      while (index < lines.length && /^\s*\d+\.\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*\d+\.\s+/, '').trim());
        index++;
      }
      index--;
      blocks.push({ type: 'ol', items });
      continue;
    }

    const paragraph = [line.trim()];
    while (index + 1 < lines.length
      && lines[index + 1].trim()
      && !lines[index + 1].trim().startsWith('```')
      && !/^(#{1,4})\s+/.test(lines[index + 1])
      && !/^\s*[-*]\s+/.test(lines[index + 1])
      && !/^\s*\d+\.\s+/.test(lines[index + 1])) {
      paragraph.push(lines[index + 1].trim());
      index++;
    }
    blocks.push({ type: 'p', text: paragraph.join(' ') });
  }
  return blocks.length ? blocks : [{ type: 'p', text: '' }];
}

function renderMarkdownBlock(block, index) {
  if (block.type === 'code') {
    return (
      <pre className="markdown-code" key={index}>
        {block.language && <span className="markdown-code-lang">{block.language}</span>}
        <code>{block.text}</code>
      </pre>
    );
  }
  if (block.type === 'heading') {
    const HeadingTag = block.level <= 2 ? 'h3' : 'h4';
    return <HeadingTag key={index}>{renderInlineMarkdown(block.text, `h-${index}`)}</HeadingTag>;
  }
  if (block.type === 'ul') {
    return (
      <ul key={index}>
        {block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInlineMarkdown(item, `u-${index}-${itemIndex}`)}</li>)}
      </ul>
    );
  }
  if (block.type === 'ol') {
    return (
      <ol key={index}>
        {block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInlineMarkdown(item, `o-${index}-${itemIndex}`)}</li>)}
      </ol>
    );
  }
  return <p key={index}>{renderInlineMarkdown(block.text, `p-${index}`)}</p>;
}

function renderInlineMarkdown(text = '', keyPrefix = 'md') {
  const parts = [];
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*)/g;
  let lastIndex = 0;
  let match;
  let partIndex = 0;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(<Fragment key={`${keyPrefix}-t-${partIndex++}`}>{text.slice(lastIndex, match.index)}</Fragment>);
    }
    const token = match[0];
    if (token.startsWith('`')) {
      parts.push(<code key={`${keyPrefix}-c-${partIndex++}`}>{token.slice(1, -1)}</code>);
    } else {
      parts.push(<strong key={`${keyPrefix}-b-${partIndex++}`}>{token.slice(2, -2)}</strong>);
    }
    lastIndex = match.index + token.length;
  }
  if (lastIndex < text.length) {
    parts.push(<Fragment key={`${keyPrefix}-t-${partIndex++}`}>{text.slice(lastIndex)}</Fragment>);
  }
  return parts;
}

export { MarkdownAnswer };
