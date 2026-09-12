import React, { useState, useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { CommandPalette } from '../ui/CommandPalette';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/theme';
import { Cloud, Menu, Moon, Search, Sun, X } from 'lucide-react';

export const Layout = ({ children }) => {
  const { user } = useAuth();
  const { theme, setTheme } = useTheme();
  const location = useLocation();

  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem('bicloud_sidebar_collapsed') === 'true';
    } catch {
      return false;
    }
  });

  const [mobileOpen, setMobileOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  useEffect(() => {
    try {
      localStorage.setItem('bicloud_sidebar_collapsed', String(collapsed));
    } catch {
      // ignore
    }
  }, [collapsed]);

  // Global Ctrl+K / Cmd+K listener
  useEffect(() => {
    const handleKeyDown = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen(prev => !prev);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const [prevPathname, setPrevPathname] = useState(location.pathname);
  if (prevPathname !== location.pathname) {
    setPrevPathname(location.pathname);
    setMobileOpen(false);
  }

  const toggleCollapsed = () => setCollapsed(prev => !prev);
  const toggleMobile = () => setMobileOpen(prev => !prev);
  const closeMobile = () => setMobileOpen(false);

  return (
    <div className={`app-shell ${collapsed ? 'sidebar-collapsed' : ''} ${mobileOpen ? 'mobile-sidebar-open' : ''}`}>
      {/* Mobile Topbar */}
      <header className="mobile-header">
        <div className="mobile-header-left">
          <button
            type="button"
            className="btn-icon mobile-menu-btn"
            onClick={toggleMobile}
            aria-label={mobileOpen ? 'Close menu' : 'Open menu'}
          >
            {mobileOpen ? <X size={20} /> : <Menu size={20} />}
          </button>

          <div className="mobile-brand">
            <div className="brand-mark small">
              <Cloud size={16} />
            </div>
            <span className="mobile-brand-name">BiCloud</span>
          </div>
        </div>

        <div className="mobile-header-right">
          <button
            type="button"
            className="btn-icon mobile-search-btn"
            onClick={() => setPaletteOpen(true)}
            title="Search (Ctrl + K)"
            aria-label="Search"
          >
            <Search size={18} />
          </button>

          <button
            type="button"
            className="btn-icon theme-quick-btn"
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
            title={`Switch to ${theme === 'dark' ? 'Light' : 'Dark'} mode`}
          >
            {theme === 'dark' ? <Sun size={17} /> : <Moon size={17} />}
          </button>

          <div className="mobile-user-avatar" title={user?.username}>
            {user?.username?.[0]?.toUpperCase()}
          </div>
        </div>
      </header>

      {/* Backdrop for mobile drawer */}
      {mobileOpen && (
        <div
          className="mobile-sidebar-backdrop"
          onClick={closeMobile}
          aria-hidden="true"
        />
      )}

      {/* Sidebar (Desktop + Mobile Drawer) */}
      <Sidebar
        collapsed={collapsed}
        onToggleCollapsed={toggleCollapsed}
        mobileOpen={mobileOpen}
        onCloseMobile={closeMobile}
        onOpenSearch={() => setPaletteOpen(true)}
      />

      {/* Main Content Area */}
      <main className="app-main">
        {children}
      </main>

      {/* Global Command Palette */}
      <CommandPalette
        isOpen={paletteOpen}
        onClose={() => setPaletteOpen(false)}
      />
    </div>
  );
};
