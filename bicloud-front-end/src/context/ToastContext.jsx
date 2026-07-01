import React, { createContext, useContext, useState, useCallback } from 'react';

const ToastContext = createContext(null);
let toastId = 0;

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);

  const remove = useCallback((id) => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  const add = useCallback((message, type = 'info', duration = 4000) => {
    const id = ++toastId;
    setToasts(prev => [...prev, { id, message, type }]);
    setTimeout(() => remove(id), duration);
  }, [remove]);

  const success = useCallback((msg) => add(msg, 'success'), [add]);
  const error   = useCallback((msg) => add(msg, 'error', 6000), [add]);
  const info    = useCallback((msg) => add(msg, 'info'), [add]);

  return (
    <ToastContext.Provider value={{ success, error, info }}>
      {children}
      <div className="toast-container" style={{
        position: 'fixed', bottom: 24, right: 24,
        display: 'flex', flexDirection: 'column', gap: 10,
        zIndex: 9999,
      }}>
        {toasts.map(t => (
          <div key={t.id} className={`toast toast-${t.type}`} onClick={() => remove(t.id)} style={{
            display: 'flex', alignItems: 'center', gap: 10,
            padding: '12px 16px',
            background: 'var(--bg-elevated)',
            border: `1px solid ${
              t.type === 'success' ? 'rgba(63,185,80,.35)' :
              t.type === 'error'   ? 'rgba(248,81,73,.35)' :
                                     'rgba(56,139,253,.35)'}`,
            borderRadius: 'var(--radius-md)',
            boxShadow: 'var(--shadow-lg)',
            animation: 'slideUp .2s ease',
            cursor: 'pointer',
            maxWidth: 380,
            color: t.type === 'success' ? 'var(--accent-green)' :
                   t.type === 'error'   ? 'var(--accent-red)'   :
                                          'var(--accent-blue)',
            fontSize: '.875rem',
            fontWeight: 500,
          }}>
            <span className="toast-icon">
              {t.type === 'success' ? '✓' : t.type === 'error' ? '✕' : 'ℹ'}
            </span>
            <span style={{ color: 'var(--text-primary)', fontWeight: 400 }}>{t.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast must be used within a ToastProvider');
  return context;
};
