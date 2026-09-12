import React from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/theme';
import {
  LayoutDashboard,
  FolderKanban,
  LogOut,
  ShieldCheck,
  Users,
  Server,
  Activity,
  History,
  ChevronRight,
  Cloud,
  Circle,
  Moon,
  Sun,
  PanelLeftClose,
  PanelLeft,
  Search,
  X,
} from 'lucide-react';

const USER_NAV = [
  { to: '/',         label: 'Overview', icon: LayoutDashboard, exact: true },
  { to: '/projects', label: 'Projects', icon: FolderKanban },
  { to: '/activity', label: 'Activity', icon: History },
];

const ADMIN_NAV = [
  { to: '/admin',          label: 'System Panel', icon: Activity },
  { to: '/admin/users',    label: 'Users',        icon: Users },
  { to: '/admin/workers',  label: 'Worker Nodes', icon: Server },
];

export const Sidebar = ({
  collapsed = false,
  onToggleCollapsed,
  mobileOpen = false,
  onCloseMobile,
  onOpenSearch,
}) => {
  const { user, logout, isAdmin } = useAuth();
  const { theme, setTheme } = useTheme();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <aside className={`shell-sidebar ${collapsed ? 'collapsed' : ''} ${mobileOpen ? 'mobile-open' : ''}`}>
      <div className="sidebar-brand">
        <div className="brand-mark">
          <Cloud size={19} />
        </div>
        {!collapsed && (
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontWeight: 800, fontSize: '.98rem', letterSpacing: 0 }}>
              BiCloud
            </div>
            <div style={{ fontSize: '.66rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.12em', marginTop: 1 }}>
              Cloud Console
            </div>
          </div>
        )}

        {/* Mobile close button */}
        <button
          type="button"
          className="btn-icon mobile-sidebar-close"
          onClick={onCloseMobile}
          aria-label="Close menu"
        >
          <X size={18} />
        </button>

        {/* Desktop collapse toggle button */}
        {onToggleCollapsed && (
          <button
            type="button"
            className="btn-icon desktop-collapse-btn"
            onClick={onToggleCollapsed}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {collapsed ? <PanelLeft size={16} /> : <PanelLeftClose size={16} />}
          </button>
        )}
      </div>

      {/* Global Search Trigger */}
      {onOpenSearch && (
        <div style={{ padding: collapsed ? '10px 8px 4px' : '10px 8px 4px' }}>
          <button
            type="button"
            className={`sidebar-search-btn ${collapsed ? 'collapsed' : ''}`}
            onClick={onOpenSearch}
            title="Global Search (Ctrl + K)"
            aria-label="Global Search"
          >
            <Search size={15} style={{ flexShrink: 0 }} />
            {!collapsed && <span style={{ flex: 1, textAlign: 'left' }}>Search...</span>}
            {!collapsed && <kbd className="sidebar-kbd">Ctrl K</kbd>}
          </button>
        </div>
      )}

      <nav className="sidebar-nav">
        <NavSection label="Workspace" collapsed={collapsed} />
        {USER_NAV.map(item => (
          <SidebarLink
            key={item.to}
            {...item}
            collapsed={collapsed}
            accentColor="var(--accent-blue)"
          />
        ))}

        {isAdmin && (
          <>
            <div style={{ margin: collapsed ? '10px 0 2px' : '16px 0 4px' }}>
              <NavSection label="Operations" accent collapsed={collapsed} />
            </div>
            {ADMIN_NAV.map(item => (
              <SidebarLink
                key={item.to}
                {...item}
                collapsed={collapsed}
                accentColor="var(--accent-purple)"
              />
            ))}
          </>
        )}
      </nav>

      <div className="sidebar-footer">
        {!collapsed && (
          <div className="theme-toggle" aria-label="Theme">
            <button
              type="button"
              className={theme === 'light' ? 'active' : ''}
              onClick={() => setTheme('light')}
            >
              <Sun size={13} /> Light
            </button>
            <button
              type="button"
              className={theme === 'dark' ? 'active' : ''}
              onClick={() => setTheme('dark')}
            >
              <Moon size={13} /> Dark
            </button>
          </div>
        )}

        <div className={`account-card ${collapsed ? 'account-card-compact' : ''}`}>
          <UserAvatar username={user?.username} />
          {!collapsed && (
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="account-name truncate" title={user?.username}>
                {user?.username}
              </div>
              <div className="account-meta">
                {isAdmin
                  ? <RoleBadge color="var(--accent-purple)" bg="rgba(163,113,247,.15)">Admin</RoleBadge>
                  : <RoleBadge color="var(--accent-blue)"   bg="rgba(56,139,253,.12)">User</RoleBadge>
                }
                <span>{isAdmin ? 'Operations' : 'Workspace'}</span>
              </div>
            </div>
          )}
          <button
            type="button"
            className="btn-icon logout-btn"
            onClick={handleLogout}
            title="Sign Out"
            style={{ flexShrink: 0 }}
            aria-label="Sign Out"
          >
            <LogOut size={16} />
          </button>
        </div>
      </div>
    </aside>
  );
};

const NavSection = ({ label, accent = false, collapsed = false }) => {
  if (collapsed) {
    return <div className="nav-section-divider" title={label} />;
  }

  return (
    <div style={{
      fontSize: '.62rem',
      fontWeight: 800,
      color: accent ? 'var(--accent-purple)' : 'var(--text-muted)',
      opacity: accent ? 0.9 : 0.7,
      textTransform: 'uppercase',
      letterSpacing: '.15em',
      padding: '12px 12px 6px',
      display: 'flex',
      alignItems: 'center',
      gap: 6,
    }}>
      {accent ? <ShieldCheck size={11} /> : <Circle size={7} />}
      {label}
    </div>
  );
};

const SidebarLink = ({ to, label, icon: Icon, exact, accentColor, collapsed }) => (
  <NavLink
    to={to}
    end={exact}
    title={collapsed ? label : undefined}
    className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''} ${collapsed ? 'collapsed' : ''}`}
    style={({ isActive }) => ({
      display: 'flex',
      alignItems: 'center',
      justifyContent: collapsed ? 'center' : 'flex-start',
      gap: collapsed ? 0 : 10,
      padding: collapsed ? '9px 0' : '9px 12px',
      borderRadius: 'var(--radius-sm)',
      textDecoration: 'none',
      fontWeight: isActive ? 600 : 400,
      fontSize: '.875rem',
      marginBottom: 2,
      background: isActive ? `${accentColor}18` : 'transparent',
      color: isActive ? accentColor : 'var(--text-secondary)',
      border: `1px solid ${isActive ? `${accentColor}35` : 'transparent'}`,
      position: 'relative',
    })}
  >
    {({ isActive }) => (
      <>
        {React.createElement(Icon, { size: 17, style: { flexShrink: 0 } })}
        {!collapsed && <span style={{ flex: 1 }} className="truncate">{label}</span>}
        {!collapsed && isActive && <ChevronRight size={12} style={{ opacity: 0.5 }} />}
      </>
    )}
  </NavLink>
);

const UserAvatar = ({ username }) => (
  <div className="account-avatar">
    {username?.[0]?.toUpperCase()}
  </div>
);

const RoleBadge = ({ children, color, bg }) => (
  <span style={{
    fontSize: '.6rem',
    fontWeight: 600,
    padding: '1px 6px',
    borderRadius: 100,
    background: bg,
    color,
    letterSpacing: '.05em',
    textTransform: 'uppercase',
    border: `1px solid ${color}40`,
  }}>
    {children}
  </span>
);

