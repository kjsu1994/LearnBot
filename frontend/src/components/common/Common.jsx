import { Component, forwardRef, useState } from 'react';
import { motion } from 'framer-motion';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { confidenceClass, getStatusLabel } from '../../lib/formatters.js';

const pageVariants = {
  hidden: { opacity: 0 },
  visible: { opacity: 1, transition: { duration: 0.42, ease: 'easeOut' } },
};

const sectionVariants = {
  hidden: { opacity: 0, y: 18 },
  visible: (delay = 0) => ({
    opacity: 1,
    y: 0,
    transition: { duration: 0.5, delay, ease: [0.22, 1, 0.36, 1] },
  }),
};

const AnimatedPage = forwardRef(function AnimatedPage({ className = '', children, ...props }, ref) {
  return (
    <motion.main ref={ref} className={className} variants={pageVariants} initial="hidden" animate="visible" {...props}>
      {children}
    </motion.main>
  );
});

const AnimatedSection = forwardRef(function AnimatedSection({ className = '', delay = 0, children, ...props }, ref) {
  return (
    <motion.section ref={ref} className={className} custom={delay} variants={sectionVariants} initial="hidden" whileInView="visible" viewport={{ once: true, amount: 0.18 }} {...props}>
      {children}
    </motion.section>
  );
});

const AnimatedContent = forwardRef(function AnimatedContent({ className = '', delay = 0, children, ...props }, ref) {
  return (
    <motion.div ref={ref} className={className} custom={delay} variants={sectionVariants} initial="hidden" animate="visible" {...props}>
      {children}
    </motion.div>
  );
});

class AppErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('RunBot UI render error', error, info);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <main className="error-boundary-screen">
        <section className="panel error-boundary-panel">
          <span className="eyebrow">Render Error</span>
          <h1>화면을 표시하지 못했습니다.</h1>
          <p>일시적인 화면 오류가 발생했습니다. 새로고침 후에도 반복되면 브라우저 콘솔 오류를 확인하세요.</p>
          <small>{this.state.error?.message || 'Unknown render error'}</small>
          <div className="action-row">
            <button type="button" onClick={() => window.location.reload()}>새로고침</button>
            <button className="ghost-button" type="button" onClick={() => { window.location.href = '/'; }}>메인으로 이동</button>
          </div>
        </section>
      </main>
    );
  }
}

function ModeControl({ modes, value, setValue, className = '' }) {
  return (
    <div className={`mode-control ${className}`} aria-label="답변 모드">
      {modes.map((mode) => (
        <button className={value === mode.value ? 'mode-button active' : 'mode-button'} key={mode.value} type="button" onClick={() => setValue(mode.value)}>
          {mode.label}
        </button>
      ))}
    </div>
  );
}

function IconButton({ children, title, disabled, onClick, danger = false }) {
  return (
    <button className={danger ? 'icon-button danger' : 'icon-button'} type="button" title={title} aria-label={title} disabled={disabled} onClick={onClick}>
      {children}
    </button>
  );
}

function StatusBadge({ status }) {
  const normalized = String(status || 'PENDING').toLowerCase();
  return <span className={`status status-${normalized}`}>{getStatusLabel(status)}</span>;
}

function AnswerStatus({ confidence, diagnostics = [] }) {
  const [open, setOpen] = useState(false);
  const items = diagnostics || [];
  if (!confidence && !items.length) return null;
  const className = confidenceClass(confidence);
  const showPreview = items.length > 0 && (
    className === 'low'
    || items.some((item) => /오류|실패|부족|대체|낮아|invalid|failed|fallback/i.test(item))
  );

  return (
    <div className={`confidence-strip confidence-${className}`}>
      <div className="confidence-summary">
        {confidence && <strong>신뢰도 {confidence}</strong>}
        {items.length > 0 && (
          <button className="ghost-button compact-action confidence-toggle" type="button" onClick={() => setOpen((current) => !current)}>
            {open ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
            {open ? '진단 접기' : `진단 ${items.length}개 보기`}
          </button>
        )}
      </div>
      {!open && showPreview && <small className="diagnostic-preview">{items[0]}</small>}
      {open && items.length > 0 && (
        <ul>
          {items.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

export { AppErrorBoundary, AnimatedPage, AnimatedSection, AnimatedContent, ModeControl, IconButton, StatusBadge, AnswerStatus };
