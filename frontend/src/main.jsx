import { createRoot } from 'react-dom/client';
import '@tabler/core/dist/css/tabler.min.css';
import 'highlight.js/styles/github-dark.css';
import './styles.css';
import './tailwind.css';
import App from './App.jsx';
import { AppErrorBoundary } from './components/common/Common.jsx';

createRoot(document.getElementById('root')).render(
  <AppErrorBoundary>
    <App />
  </AppErrorBoundary>,
);
