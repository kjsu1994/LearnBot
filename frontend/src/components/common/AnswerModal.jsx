import { useEffect } from 'react';
import { MessageSquare, X } from 'lucide-react';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';

function AnswerModal({ title = '답변', subtitle = '', answer = '', onClose }) {
  useEffect(() => {
    function handleKeyDown(event) {
      if (event.key === 'Escape') onClose?.();
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  return (
    <div className="code-modal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className="code-modal answer-modal" role="dialog" aria-modal="true" aria-labelledby="answer-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            <MessageSquare size={18} />
            <div>
              <h2 id="answer-modal-title">{title}</h2>
              {subtitle && <p>{subtitle}</p>}
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title="닫기" onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>
        <div className="code-modal-tabs" aria-hidden="true">
          <span className="active-tab">답변</span>
          <span>expanded view</span>
        </div>
        <div className="code-modal-body answer-modal-body">
          <MarkdownAnswer text={answer} />
        </div>
      </section>
    </div>
  );
}

export { AnswerModal };
