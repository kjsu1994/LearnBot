import { createRoot } from 'react-dom/client';
import 'highlight.js/styles/github-dark.css';
import './styles.css';
import App from './App.jsx';
import { AppErrorBoundary } from './components/common/Common.jsx';

createRoot(document.getElementById('root')).render(
  <AppErrorBoundary>
    <App />
  </AppErrorBoundary>,
);
