import { useEffect, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import { FileCode2, Loader2, X } from 'lucide-react';
import { escapeHtml, highlightLanguage, highlightedLineHtml } from '../../lib/highlight.js';

const SPACE_PLACEHOLDER = '\u00A0';

function sanitizeHighlightClassName(className) {
  return String(className || '')
    .split(/\s+/)
    .map((token) => token.trim())
    .filter((token) => token && /^(?:hljs|hljs-[\w-]+)$/.test(token))
    .join(' ');
}

function parseHighlightedLine(lineMarkup, fallback) {
  const rawFallback = fallback || SPACE_PLACEHOLDER;
  if (!lineMarkup || typeof lineMarkup !== 'string') {
    return rawFallback;
  }
  if (lineMarkup.indexOf('<') < 0) {
    return lineMarkup || rawFallback;
  }

  if (typeof DOMParser === 'undefined' || typeof window === 'undefined' || typeof document === 'undefined') {
    return lineMarkup || rawFallback;
  }

  try {
    const parser = new DOMParser();
    const parsed = parser.parseFromString(`<span>${lineMarkup}</span>`, 'text/html');
    const root = parsed.body?.firstElementChild;
    if (!root) return lineMarkup || rawFallback;

    function renderNodes(node) {
      if (!node || !node.childNodes?.length) {
        return null;
      }
      return Array.from(node.childNodes).map((child, index) => {
        if (child.nodeType === Node.TEXT_NODE) {
          return <span key={`text-${index}`}>{child.textContent || ''}</span>;
        }

        if (child.nodeType === Node.ELEMENT_NODE) {
          const tagName = String(child.tagName || '').toLowerCase();
          if (tagName === 'span') {
            const className = sanitizeHighlightClassName(child.getAttribute('class'));
            return (
              <span key={`span-${index}`} className={className || undefined}>
                {renderNodes(child)}
              </span>
            );
          }
          return (
            <span key={`text-${index}`} className="whitespace-pre-wrap">
              {child.textContent || ''}
            </span>
          );
        }

        return null;
      });
    }

    const rendered = renderNodes(root);
    if (!rendered || rendered.length === 0) {
      return rawFallback;
    }
    return rendered;
  } catch {
    return escapeHtml(lineMarkup) || rawFallback;
  }
}

function CodeFileModal({ detail, highlightRange, loading, onClose }) {
  const highlightedLineRef = useRef(null);
  const lines = detail?.content ? detail.content.split(/\r?\n/) : [];
  const highlightRanges = normalizeHighlightRanges(highlightRange);
  const firstHighlightRange = highlightRanges[0] || null;
  const fileName = detail?.filePath?.split(/[\\/]/).pop() || 'code';
  const language = detail?.language || 'code';
  const syntaxLanguage = highlightLanguage(detail?.filePath, language);
  const renderedLines = useMemo(
    () => lines.map((line) => parseHighlightedLine(highlightedLineHtml(line, syntaxLanguage), line)),
    [detail?.content, syntaxLanguage]
  );
  const chunkCount = detail?.chunks?.length || 0;

  useEffect(() => {
    function handleKeyDown(event) {
      if (event.key === 'Escape') onClose?.();
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    if (loading || !firstHighlightRange?.start || !highlightedLineRef.current) return undefined;
    const timer = window.setTimeout(() => {
      highlightedLineRef.current?.scrollIntoView({ block: 'center' });
    }, 80);
    return () => window.clearTimeout(timer);
  }, [detail?.id, firstHighlightRange?.start, firstHighlightRange?.end, loading]);

  const modal = (
    <div className="code-modal-backdrop source-modal-portal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className="code-modal" role="dialog" aria-modal="true" aria-labelledby="code-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            <FileCode2 size={18} />
            <div>
              <h2 id="code-modal-title">{fileName}</h2>
              <p>{detail?.filePath || '肄붾뱶 ?뚯씪??遺덈윭?ㅻ뒗 以묒엯?덈떎.'}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title={'?リ린'} onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>

        <div className="code-modal-tabs" aria-hidden="true">
          <span className="active-tab">{fileName}</span>
          <span>{language}</span>
          {highlightRanges.length > 0 && <span>{highlightRangeLabel(highlightRanges)}</span>}
        </div>

        <div className="code-modal-body">
          {loading && (
            <div className="code-modal-state">
              <Loader2 className="spin" size={22} />
              <strong>{'肄붾뱶 ?뚯씪??遺덈윭?ㅻ뒗 以묒엯?덈떎.'}</strong>
            </div>
          )}

          {!loading && !detail && (
            <div className="code-modal-state">
              <FileCode2 size={22} />
              <strong>{'?쒖떆??肄붾뱶媛 ?놁뒿?덈떎.'}</strong>
            </div>
          )}

          {!loading && detail && (
            <pre className="ide-code-viewer">
              <code>
                {lines.map((line, index) => {
                  const lineNumber = index + 1;
                  const highlighted = isLineHighlighted(lineNumber, highlightRanges);
                  return (
                    <div
                      className={highlighted ? 'ide-code-line highlighted-line' : 'ide-code-line'}
                      key={lineNumber}
                      ref={lineNumber === firstHighlightRange?.start ? highlightedLineRef : null}
                    >
                      <span className="ide-line-number">{lineNumber}</span>
                      <span className="ide-line-content">{renderedLines[index] || SPACE_PLACEHOLDER}</span>
                    </div>
                  );
                })}
              </code>
            </pre>
          )}
        </div>

        <footer className="code-modal-status">
          <span>{language}</span>
          <span>{lines.length} lines</span>
          <span>{chunkCount} chunks</span>
        </footer>
      </section>
    </div>
  );

  if (typeof document === 'undefined') return modal;
  return createPortal(modal, document.body);
}

function CodeFileViewer({ detail, highlightRange, loading }) {
  if (loading) {
    return (
      <section className="panel">
        <div className="panel-title">
          <FileCode2 size={18} />
          <div>
            <h2>{'肄붾뱶 誘몃━蹂닿린'}</h2>
            <p>{'?뚯씪??遺덈윭?ㅻ뒗 以묒엯?덈떎.'}</p>
          </div>
        </div>
      </section>
    );
  }
  if (!detail) {
    return (
      <section className="panel muted-panel">
        <div className="panel-title">
          <FileCode2 size={18} />
          <div>
            <h2>{'肄붾뱶 誘몃━蹂닿린'}</h2>
            <p>{'?뚯씪?대굹 洹쇨굅瑜??좏깮?섎㈃ ?먮Ц 肄붾뱶瑜??뺤씤?????덉뒿?덈떎.'}</p>
          </div>
        </div>
      </section>
    );
  }
  const lines = detail.content.split(/\r?\n/);
  const highlightRanges = normalizeHighlightRanges(highlightRange);
  return (
    <section className="panel">
      <div className="panel-title">
        <FileCode2 size={18} />
        <div>
          <h2>{detail.filePath}</h2>
          <p>{detail.language} {'쨌'} {detail.chunks?.length || 0} chunks</p>
        </div>
      </div>
      <pre className="code-viewer">
        <code>
          {lines.map((line, index) => {
            const lineNumber = index + 1;
            const highlighted = isLineHighlighted(lineNumber, highlightRanges);
            return (
              <div className={highlighted ? 'highlighted-line' : ''} key={lineNumber}>
                <span>{lineNumber}</span>{line || ' '}
              </div>
            );
          })}
        </code>
      </pre>
    </section>
  );
}

function normalizeHighlightRanges(highlightRange) {
  const ranges = Array.isArray(highlightRange) ? highlightRange : highlightRange ? [highlightRange] : [];
  return ranges
    .map((range) => ({
      start: Number(range?.start || 0),
      end: Number(range?.end || range?.start || 0),
    }))
    .filter((range) => range.start > 0 && range.end >= range.start)
    .sort((left, right) => left.start - right.start || left.end - right.end);
}

function isLineHighlighted(lineNumber, ranges = []) {
  return ranges.some((range) => lineNumber >= range.start && lineNumber <= range.end);
}

function highlightRangeLabel(ranges = []) {
  if (!ranges.length) return '';
  if (ranges.length === 1) return `lines ${ranges[0].start}-${ranges[0].end}`;
  return `${ranges.length} ranges 쨌 ${ranges.slice(0, 3).map((range) => `${range.start}-${range.end}`).join(', ')}${ranges.length > 3 ? ` +${ranges.length - 3}` : ''}`;
}

export { CodeFileModal, CodeFileViewer };
