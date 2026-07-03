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

export const Sidebar = () => {
  const { user, logout, isAdmin } = useAuth();
  const { theme, setTheme } = useTheme();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <aside className="shell-sidebar">
      <div className="sidebar-brand">
        <div className="brand-mark">
          <Cloud size={19} />
        </div>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontWeight: 800, fontSize: '.98rem', letterSpacing: 0 }}>
            BiCloud
          </div>
          <div style={{ fontSize: '.66rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.12em', marginTop: 1 }}>
            Cloud Console
          </div>
        </div>
      </div>

      <nav className="sidebar-nav">
        <NavSection label="Workspace" />
        {USER_NAV.map(item => (
          <SidebarLink key={item.to} {...item} accentColor="var(--accent-blue)" />
        ))}

        {isAdmin && (
          <>
            <div style={{ margin: '16px 0 4px' }}>
              <NavSection label="Operations" accent />
            </div>
            {ADMIN_NAV.map(item => (
              <SidebarLink key={item.to} {...item} accentColor="var(--accent-purple)" />
            ))}
          </>
        )}
      </nav>

      <div className="sidebar-footer">
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

        <div className="account-card">
          <UserAvatar username={user?.username} />
          <div style={{ minWidth: 0 }}>
            <div className="account-name">
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
          <button
            className="btn-icon logout-btn"
            onClick={handleLogout}
            title="Sign Out"
            style={{ flexShrink: 0 }}
          >
            <LogOut size={16} />
          </button>
        </div>
      </div>
    </aside>
  );
};

const NavSection = ({ label, accent = false }) => (
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

const SidebarLink = ({ to, label, icon: Icon, exact, accentColor }) => (
  <NavLink
    to={to}
    end={exact}
    className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}
    style={({ isActive }) => ({
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '9px 12px',
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
        {React.createElement(Icon, { size: 16, style: { flexShrink: 0 } })}
        <span style={{ flex: 1 }}>{label}</span>
        {isActive && <ChevronRight size={12} style={{ opacity: 0.5 }} />}
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
