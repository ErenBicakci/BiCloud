import React, { useState, useEffect, useRef, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { projectService } from '../../services/project.service';
import { workerService } from '../../services/worker.service';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/theme';
import {
  Activity,
  ArrowRight,
  Boxes,
  Cloud,
  CornerDownLeft,
  FolderKanban,
  History,
  Layers,
  LayoutDashboard,
  LogOut,
  Moon,
  Plus,
  Search,
  Server,
  Sun,
  Users,
  X,
} from 'lucide-react';

export const CommandPalette = ({ isOpen, onClose }) => {
  const navigate = useNavigate();
  const { user, isAdmin, logout } = useAuth();
  const { theme, setTheme } = useTheme();

  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [projects, setProjects] = useState([]);
  const [workers, setWorkers] = useState([]);
  const inputRef = useRef(null);
  const listRef = useRef(null);

  const [prevIsOpen, setPrevIsOpen] = useState(isOpen);
  if (prevIsOpen !== isOpen) {
    setPrevIsOpen(isOpen);
    if (!isOpen) {
      setQuery('');
      setSelectedIndex(0);
    }
  }

  // Fetch projects and workers when palette opens
  useEffect(() => {
    if (!isOpen) return;

    let cancelled = false;
    projectService.list()
      .then(res => {
        if (!cancelled) setProjects(res.data || []);
      })
      .catch(() => {});

    if (isAdmin) {
      workerService.list()
        .then(res => {
          if (!cancelled) setWorkers(res.data || []);
        })
        .catch(() => {});
    }

    // Auto-focus search input
    setTimeout(() => {
      inputRef.current?.focus();
    }, 50);

    return () => { cancelled = true; };
  }, [isOpen, isAdmin]);

  // Build searchable items
  const items = useMemo(() => {
    const q = query.toLowerCase().trim();
    const result = [];

    // 1. Static navigation actions
    const baseNav = [
      { id: 'nav-overview', title: 'Overview', category: 'Navigation', icon: LayoutDashboard, path: '/' },
      { id: 'nav-projects', title: 'Projects', category: 'Navigation', icon: FolderKanban, path: '/projects' },
      { id: 'nav-activity', title: 'Activity & Audit Logs', category: 'Navigation', icon: History, path: '/activity' },
    ];

    if (isAdmin) {
      baseNav.push(
        { id: 'nav-admin', title: 'System Panel', category: 'Admin', icon: Activity, path: '/admin' },
        { id: 'nav-users', title: 'User Management', category: 'Admin', icon: Users, path: '/admin/users' },
        { id: 'nav-workers', title: 'Worker Nodes', category: 'Admin', icon: Server, path: '/admin/workers' },
      );
    }

    // Filter Navigation
    baseNav.forEach(item => {
      if (!q || item.title.toLowerCase().includes(q)) {
        result.push({ ...item, type: 'action', perform: () => navigate(item.path) });
      }
    });

    // 2. Projects
    projects.forEach(p => {
      if (!q || p.name.toLowerCase().includes(q) || p.description?.toLowerCase().includes(q)) {
        result.push({
          id: `proj-${p.id}`,
          title: p.name,
          subtitle: `${p.images?.length || 0} service(s) • ${p.totalRunningContainers || 0} running`,
          category: 'Projects',
          icon: FolderKanban,
          badge: p.totalRunningContainers > 0 ? 'Active' : 'Idle',
          badgeColor: p.totalRunningContainers > 0 ? 'var(--accent-green)' : 'var(--text-muted)',
          type: 'project',
          perform: () => navigate(`/projects/${p.id}`),
        });
      }

      // 3. Services under projects
      (p.images || []).forEach(img => {
        if (!q || img.serviceName.toLowerCase().includes(q) || img.imageName.toLowerCase().includes(q)) {
          result.push({
            id: `svc-${img.id}`,
            title: `${img.serviceName}`,
            subtitle: `Project: ${p.name} • ${img.imageName}`,
            category: 'Services',
            icon: Layers,
            badge: `${img.runningReplicas ?? 0}/${img.desiredReplicas} replicas`,
            badgeColor: (img.runningReplicas ?? 0) === img.desiredReplicas ? 'var(--accent-green)' : 'var(--accent-yellow)',
            type: 'service',
            perform: () => navigate(`/projects/${p.id}/services/${img.id}`),
          });
        }
      });
    });

    // 4. Worker Nodes
    if (isAdmin) {
      workers.forEach(w => {
        if (!q || w.workerName.toLowerCase().includes(q) || w.ipAddress?.includes(q)) {
          result.push({
            id: `worker-${w.workerId}`,
            title: w.workerName,
            subtitle: `IP: ${w.ipAddress || 'unknown'} • ${w.totalCpuCores} Cores, ${w.totalMemoryMb} MB`,
            category: 'Worker Nodes',
            icon: Server,
            badge: w.status,
            badgeColor: w.status === 'ACTIVE' ? 'var(--accent-green)' : w.status === 'MAINTENANCE' ? 'var(--accent-yellow)' : 'var(--accent-red)',
            type: 'worker',
            perform: () => navigate(`/admin/workers/${w.workerId}`),
          });
        }
      });
    }

    // 5. Quick Utility Actions
    if (!q || 'theme dark light mode'.includes(q)) {
      result.push({
        id: 'util-theme',
        title: `Switch Theme to ${theme === 'dark' ? 'Light' : 'Dark'} Mode`,
        category: 'Quick Actions',
        icon: theme === 'dark' ? Sun : Moon,
        type: 'action',
        perform: () => setTheme(theme === 'dark' ? 'light' : 'dark'),
      });
    }

    if (!q || 'logout sign out exit'.includes(q)) {
      result.push({
        id: 'util-logout',
        title: `Sign Out (${user?.username})`,
        category: 'Quick Actions',
        icon: LogOut,
        type: 'action',
        perform: () => {
          logout();
          navigate('/login');
        },
      });
    }

    return result;
  }, [query, projects, workers, isAdmin, user, theme, navigate, setTheme, logout]);

  // Keyboard navigation inside palette
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e) => {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIndex(prev => (prev < items.length - 1 ? prev + 1 : 0));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIndex(prev => (prev > 0 ? prev - 1 : items.length - 1));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (items[selectedIndex]) {
          items[selectedIndex].perform();
          onClose();
        }
      } else if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, items, selectedIndex, onClose]);

  // Scroll active item into view
  useEffect(() => {
    if (listRef.current) {
      const activeEl = listRef.current.querySelector('.cmd-item.active');
      if (activeEl) {
        activeEl.scrollIntoView({ block: 'nearest' });
      }
    }
  }, [selectedIndex]);

  if (!isOpen) return null;

  return (
    <div className="cmd-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="cmd-palette">
        <div className="cmd-search-bar">
          <Search size={18} className="cmd-search-icon" />
          <input
            ref={inputRef}
            type="text"
            className="cmd-input"
            placeholder="Search projects, services, worker nodes, or actions..."
            value={query}
            onChange={e => {
              setQuery(e.target.value);
              setSelectedIndex(0);
            }}
          />
          {query && (
            <button className="btn-icon" onClick={() => setQuery('')} aria-label="Clear search">
              <X size={15} />
            </button>
          )}
          <kbd className="cmd-kbd">ESC</kbd>
        </div>

        <div className="cmd-list" ref={listRef}>
          {items.length === 0 ? (
            <div className="cmd-empty">
              <p>No matching results for "{query}"</p>
            </div>
          ) : (
            items.map((item, idx) => {
              const Icon = item.icon || Layers;
              const isSelected = idx === selectedIndex;

              return (
                <div
                  key={item.id}
                  className={`cmd-item ${isSelected ? 'active' : ''}`}
                  onClick={() => {
                    item.perform();
                    onClose();
                  }}
                  onMouseEnter={() => setSelectedIndex(idx)}
                >
                  <div className="cmd-item-icon">
                    <Icon size={16} />
                  </div>
                  <div className="cmd-item-body">
                    <div className="cmd-item-title-row">
                      <span className="cmd-item-title">{item.title}</span>
                      {item.badge && (
                        <span
                          className="cmd-item-badge"
                          style={{ borderColor: item.badgeColor, color: item.badgeColor }}
                        >
                          {item.badge}
                        </span>
                      )}
                    </div>
                    {item.subtitle && (
                      <span className="cmd-item-subtitle">{item.subtitle}</span>
                    )}
                  </div>
                  <span className="cmd-item-category">{item.category}</span>
                  {isSelected && (
                    <CornerDownLeft size={13} className="cmd-item-enter-icon" />
                  )}
                </div>
              );
            })
          )}
        </div>

        <div className="cmd-footer">
          <div className="cmd-footer-tips">
            <span><kbd className="cmd-kbd-inline">↑</kbd> <kbd className="cmd-kbd-inline">↓</kbd> navigate</span>
            <span><kbd className="cmd-kbd-inline">↵</kbd> select</span>
            <span><kbd className="cmd-kbd-inline">ESC</kbd> close</span>
          </div>
          <div className="cmd-footer-brand">BiCloud Global Search</div>
        </div>
      </div>
    </div>
  );
};
