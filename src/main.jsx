import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './styles.css';

class AppErrorBoundary extends React.Component {
  state = { error: null };
  static getDerivedStateFromError(error) { return { error }; }
  componentDidCatch(error) { console.error('JaMusic failed to render:', error); }
  render() {
    if (this.state.error) return <main className="boot-error"><p className="eyebrow">JA MUSIC COULD NOT START</p><h1>Something went wrong.</h1><p>Reload the page. If this keeps happening, check the browser console and Cloudflare Pages build output.</p><pre>{this.state.error.message}</pre></main>;
    return this.props.children;
  }
}

createRoot(document.getElementById('root')).render(
  <React.StrictMode><AppErrorBoundary><App /></AppErrorBoundary></React.StrictMode>,
);
