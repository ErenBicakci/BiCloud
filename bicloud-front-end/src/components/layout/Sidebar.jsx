import React from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import {
  LayoutDashboard,
  FolderKanban,
  LogOut,
  Hexagon,
  ShieldCheck,
  Users,
  Server,
  Activity,
  History,
  ChevronRight,
} from 'lucide-react';

const USER_NAV = [
  { to: '/',         label: 'Overview',  icon: LayoutDashboard, exact: true },
  { to: '/projects', label: 'Projects',  icon: FolderKanban },
  { to: '/activity', label: 'Activity',  icon: History },
];

const ADMIN_NAV = [
  { to: '/admin',          label: 'System Panel', icon: Activity },
  { to: '/admin/users',    label: 'Users',        icon: Users },
  { to: '/admin/workers',  label: 'Worker Nodes', icon: Server },
];

export const Sidebar = () => {
  const { user, logout, isAdmin } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <aside style={{
      width: 'var(--sidebar-width)',
      background: 'var(--bg-surface)',
      borderRight: '1px solid var(--border-subtle)',
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      position: 'sticky',
      top: 0,
      flexShrink: 0,
    }}>
      {/* Brand */}
      <div style={{
        padding: '18px 16px',
        borderBottom: '1px solid var(--border-subtle)',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
      }}>
        <div style={{
          background: 'linear-gradient(135deg, rgba(56,139,253,.25), rgba(57,197,207,.15))',
          border: '1px solid rgba(56,139,253,.35)',
          borderRadius: 10,
          padding: '7px',
          color: 'var(--accent-blue)',
          display: 'flex',
          boxShadow: '0 0 16px rgba(56,139,253,.25)',
        }}>
          <Hexagon size={20} />
        </div>
        <div>
          <div style={{ fontWeight: 800, fontSize: '.95rem', letterSpacing: '-.01em' }}>
            <span className="gradient-text">BiCloud</span>
          </div>
          <div style={{ fontSize: '.6rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.12em' }}>
            {isAdmin ? 'Admin Console' : 'Portal'}
          </div>
        </div>
      </div>

      {/* Nav */}
      <nav style={{ flex: 1, padding: '12px 8px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 0 }}>

        {/* User Section */}
        <NavSection label="My Services" />
        {USER_NAV.map(item => (
          <SidebarLink key={item.to} {...item} accentColor="var(--accent-blue)" />
        ))}

        {/* Admin Section */}
        {isAdmin && (
          <>
            <div style={{ margin: '16px 0 4px' }}>
              <NavSection label="Administration" accent />
            </div>
            {ADMIN_NAV.map(item => (
              <SidebarLink key={item.to} {...item} accentColor="var(--accent-purple)" />
            ))}
          </>
        )}
      </nav>

      {/* User Profile Footer */}
      <div style={{ padding: '10px 8px', borderTop: '1px solid var(--border-subtle)' }}>
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '10px 12px',
          borderRadius: 'var(--radius-md)',
          background: 'rgba(255,255,255,0.02)',
          border: '1px solid var(--border-subtle)',
          cursor: 'pointer',
          transition: 'all var(--transition)',
        }}
          onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--border-default)'; e.currentTarget.style.background = 'rgba(255,255,255,0.04)'; }}
          onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border-subtle)'; e.currentTarget.style.background = 'rgba(255,255,255,0.02)'; }}
        >
          <UserAvatar username={user?.username} isAdmin={isAdmin} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: '.85rem', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {user?.username}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 2 }}>
              {isAdmin
                ? <RoleBadge color="var(--accent-purple)" bg="rgba(163,113,247,.15)">Admin</RoleBadge>
                : <RoleBadge color="var(--accent-blue)"   bg="rgba(56,139,253,.12)">User</RoleBadge>
              }
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
    {accent && <ShieldCheck size={11} />}
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
      background: isActive ? `linear-gradient(90deg, ${accentColor}1a 0%, transparent 100%)` : 'transparent',
      color: isActive ? accentColor : 'var(--text-secondary)',
      boxShadow: isActive ? `inset 3px 0 0 ${accentColor}` : 'none',
      position: 'relative',
    })}
  >
    {({ isActive }) => (
      <>
        <Icon size={16} style={{ flexShrink: 0 }} />
        <span style={{ flex: 1 }}>{label}</span>
        {isActive && <ChevronRight size={12} style={{ opacity: 0.5 }} />}
      </>
    )}
  </NavLink>
);

const UserAvatar = ({ username, isAdmin }) => (
  <div style={{
    width: 30,
    height: 30,
    borderRadius: '50%',
    background: isAdmin ? 'rgba(163,113,247,.2)' : 'rgba(56,139,253,.15)',
    border: `1px solid ${isAdmin ? 'rgba(163,113,247,.3)' : 'rgba(56,139,253,.25)'}`,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '.75rem',
    fontWeight: 700,
    color: isAdmin ? 'var(--accent-purple)' : 'var(--accent-blue)',
    flexShrink: 0,
  }}>
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
