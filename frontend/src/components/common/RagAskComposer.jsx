import { Command, Loader2, Send, Sparkles } from 'lucide-react';

function RagAskComposer({
  title,
  description,
  icon,
  controls,
  guide,
  value = '',
  setValue = () => {},
  onKeyDown,
  placeholder = '',
  loading = false,
  disabled = false,
  submitLabel = 'Send',
  templates = [],
  footer,
}) {
  function applyTemplate(template) {
    if (!template?.prompt || value.trim()) return;
    setValue(template.prompt);
  }

  return (
    <div className="rag-ai-composer">
      <div className="rag-ai-composer-glow" aria-hidden="true" />
      <div className="rag-ai-composer-header">
        <span className="rag-ai-composer-icon">{icon || <Sparkles size={18} />}</span>
        <div>
          <h2>{title}</h2>
          {description && <p>{description}</p>}
        </div>
      </div>

      {controls && <div className="rag-ai-composer-controls">{controls}</div>}

      <div className="rag-ai-input-shell">
        <textarea
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={placeholder}
        />
        <div className="rag-ai-input-footer">
          <div className="rag-ai-input-tools">
            <span className="rag-ai-tool-pill">
              <Command size={14} />
              Source-grounded
            </span>
            {guide}
          </div>
          <button className="rag-ai-send-button" disabled={disabled || loading} type="submit">
            {loading ? <Loader2 className="spin" size={16} /> : <Send size={16} />}
            {submitLabel}
          </button>
        </div>
      </div>

      {templates.length > 0 && (
        <div className="rag-ai-template-row" aria-label="질문 템플릿">
          {templates.map((template) => (
            <button
              key={template.label}
              type="button"
              title={value.trim() ? '입력 중인 질문이 있어 템플릿을 적용하지 않습니다.' : template.prompt}
              onClick={() => applyTemplate(template)}
            >
              {template.icon && <span>{template.icon}</span>}
              {template.label}
            </button>
          ))}
        </div>
      )}

      {footer && <div className="rag-ai-composer-footer">{footer}</div>}
    </div>
  );
}

export { RagAskComposer };
