import React from 'react';
import { Sidebar } from './Sidebar';

export const Layout = ({ children }) => {
  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <Sidebar />
      <main style={{ flex: 1, minWidth: 0, overflowX: 'hidden', background: 'var(--bg-base)' }}>
        {children}
      </main>
    </div>
  );
};
