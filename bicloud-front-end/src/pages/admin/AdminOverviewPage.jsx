import React, { useState, useEffect, useCallback } from 'react';
import { projectService } from '../../services/project.service';
import { workerService } from '../../services/worker.service';
import { adminService } from '../../services/admin.service';
import { extractError } from '../../utils/common';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { Card, Badge, Spinner } from '../../components/ui';
import { Link } from 'react-router-dom';
import {
  Server,
  Users,
  FolderKanban,
  Box,
  CheckCircle2,
  AlertTriangle,
  ArrowRight,
  RefreshCw,
  Activity,
  Cpu,
  MemoryStick,
  Radio,
} from 'lucide-react';

export default function AdminOverviewPage() {
  const { error, success } = useToast();
  const { isAdmin } = useAuth();
  const [projects, setProjects] = useState([]);
  const [workers,  setWorkers]  = useState([]);
  const [users,    setUsers]    = useState([]);
  const [loading,  setLoading]  = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [resyncing,  setResyncing]  = useState(false);

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    try {
      const [pRes, wRes, uRes] = await Promise.all([
        projectService.list(),
        workerService.list(),
        adminService.listUsers(),
      ]);
      setProjects(pRes.data || []);
      setWorkers(wRes.data  || []);
      setUsers(uRes.data    || []);
    } catch (err) {
      error(extractError(err));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [error]);

  useEffect(() => {
    load();
    const interval = setInterval(() => load(true), 30000);
    return () => clearInterval(interval);
  }, [load]);

  const handleResync = async () => {
    setResyncing(true);
    try {
      const { data } = await adminService.resyncGateway();
      success(`Gateway resync complete — ${data.registered} containers re-registered.`);
    } catch (err) {
      error(extractError(err));
    } finally {
      setResyncing(false);
    }
  };

  const totalContainers = projects.reduce((s, p) => s + (p.totalRunningContainers || 0), 0);
  const activeWorkers   = workers.filter(w => w.status === 'ACTIVE').length;
  const failedWorkers   = workers.length - activeWorkers;

  const stats = [
    { label: 'Total Users',       value: users.length,      icon: Users,         color: 'var(--accent-blue)',   bg: 'rgba(56,139,253,.1)',   link: '/admin/users' },
    { label: 'Total Projects',     value: projects.length,   icon: FolderKanban,  color: 'var(--accent-cyan)',   bg: 'rgba(57,197,207,.1)' },
    { label: 'Running Containers', value: totalContainers,   icon: Box,           color: 'var(--accent-green)',  bg: 'rgba(63,185,80,.1)' },
    { label: 'Worker Nodes',       value: workers.length,    icon: Server,        color: 'var(--accent-purple)', bg: 'rgba(163,113,247,.1)',  link: '/admin/workers' },
  ];

  if (loading) return <div className="page-loader"><Spinner size="lg" /></div>;

  return (
    <div style={{ padding: '32px', maxWidth: 1300 }}>

      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 36 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
            <div style={{ padding: '6px 10px', background: 'rgba(163,113,247,.12)', border: '1px solid rgba(163,113,247,.25)', borderRadius: 8, display: 'flex', alignItems: 'center', gap: 6 }}>
              <Activity size={14} style={{ color: 'var(--accent-purple)' }} />
              <span style={{ fontSize: '.7rem', fontWeight: 700, color: 'var(--accent-purple)', textTransform: 'uppercase', letterSpacing: '.08em' }}>System Management</span>
            </div>
          </div>
          <h1 style={{ fontSize: '1.6rem', fontWeight: 800, marginBottom: 6, letterSpacing: '-.02em' }}>
            System Overview
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '.9rem' }}>
            All platform resources and infrastructure status.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <button
            className="btn btn-ghost btn-sm"
            onClick={handleResync}
            disabled={resyncing}
            title="Re-register all RUNNING containers to the gateway"
            style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--accent-cyan)', borderColor: 'rgba(57,197,207,.3)' }}
          >
            <Radio size={13} style={{ animation: resyncing ? 'spin .7s linear infinite' : 'none' }} />
            {resyncing ? 'Resync...' : 'Gateway Resync'}
          </button>
          <button
            className="btn btn-ghost"
            onClick={() => load(true)}
            disabled={refreshing}
            style={{ display: 'flex', alignItems: 'center', gap: 6 }}
          >
            <RefreshCw size={14} style={{ animation: refreshing ? 'spin .7s linear infinite' : 'none' }} />
            Refresh
          </button>
        </div>
      </div>

      {/* System Stats */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
        gap: 16,
        marginBottom: 32,
      }}>
        {stats.map(s => <AdminStatCard key={s.label} {...s} />)}
      </div>

      {/* Worker Health + Projects Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24, marginBottom: 24 }}>

        {/* Worker Health */}
        <Card style={{ padding: 0, overflow: 'hidden' }}>
          <SectionHeader
            icon={Server}
            iconColor="var(--accent-purple)"
            iconBg="rgba(163,113,247,.1)"
            title="Worker Nodes"
            subtitle={
              <div style={{ display: 'flex', gap: 8 }}>
                {activeWorkers > 0 && (
                  <StatusPill color="var(--accent-green)" bg="rgba(63,185,80,.12)">
                    <CheckCircle2 size={10} /> {activeWorkers} Active
                  </StatusPill>
                )}
                {failedWorkers > 0 && (
                  <StatusPill color="var(--accent-red)" bg="rgba(248,81,73,.12)">
                    <AlertTriangle size={10} /> {failedWorkers} Degraded
                  </StatusPill>
                )}
              </div>
            }
            link={{ to: '/admin/workers', label: 'View All' }}
          />
          <div style={{ padding: '8px 0' }}>
            {workers.length === 0 ? (
              <EmptyState message="No registered workers found." />
            ) : (
              workers.slice(0, 5).map((w, idx) => (
                <WorkerRow key={w.workerId} worker={w} isLast={idx === Math.min(workers.length, 5) - 1} />
              ))
            )}
          </div>
        </Card>

        {/* All Projects */}
        <Card style={{ padding: 0, overflow: 'hidden' }}>
          <SectionHeader
            icon={FolderKanban}
            iconColor="var(--accent-cyan)"
            iconBg="rgba(57,197,207,.1)"
            title="All Projects"
            subtitle={<span style={{ fontSize: '.75rem', color: 'var(--text-muted)' }}>{projects.length} projects</span>}
          />
          <div style={{ padding: '8px 0' }}>
            {projects.length === 0 ? (
              <EmptyState message="No projects created yet." />
            ) : (
              projects.slice(0, 6).map((p, idx) => (
                <AdminProjectRow key={p.id} project={p} isLast={idx === Math.min(projects.length, 6) - 1} />
              ))
            )}
          </div>
        </Card>
      </div>

      {/* Users Summary */}
      <Card style={{ padding: 0, overflow: 'hidden' }}>
        <SectionHeader
          icon={Users}
          iconColor="var(--accent-blue)"
          iconBg="rgba(56,139,253,.1)"
          title="Recently Registered Users"
          link={{ to: '/admin/users', label: 'Manage All' }}
        />
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>User</th>
                <th>Role</th>
                <th style={{ textAlign: 'right' }}>Project Count</th>
              </tr>
            </thead>
            <tbody>
              {users.slice(0, 6).map(u => {
                const userProjectCount = projects.filter(p => p.ownerUsername === u.username).length;
                return (
                  <tr key={u.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <UserAvatar username={u.username} isAdmin={u.role === 'ADMIN'} />
                        <span style={{ fontWeight: 600 }}>{u.username}</span>
                      </div>
                    </td>
                    <td>
                      <Badge variant={u.role === 'ADMIN' ? 'purple' : 'blue'}>{u.role}</Badge>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <span style={{ fontSize: '.85rem', color: 'var(--text-secondary)' }}>
                        {userProjectCount} projects
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}

/* ─── Sub-components ─────────────────────────────────────────────────────── */

const AdminStatCard = ({ label, value, icon: Icon, color, bg, link }) => {
  const inner = (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 16,
      padding: '18px 20px',
      background: 'var(--bg-card)',
      border: '1px solid var(--border-subtle)',
      borderRadius: 'var(--radius-lg)',
      transition: 'border-color var(--transition)',
      cursor: link ? 'pointer' : 'default',
    }}
      onMouseEnter={e => { e.currentTarget.style.borderColor = color + '55'; }}
      onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border-subtle)'; }}
    >
      <div style={{ padding: 10, background: bg, borderRadius: 10, color, flexShrink: 0 }}>
        <Icon size={20} />
      </div>
      <div>
        <div style={{ fontSize: '.7rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 4 }}>
          {label}
        </div>
        <div style={{ fontSize: '1.75rem', fontWeight: 800, lineHeight: 1, color: 'var(--text-primary)' }}>
          {value}
        </div>
      </div>
    </div>
  );

  if (link) return <Link to={link} style={{ textDecoration: 'none' }}>{inner}</Link>;
  return inner;
};

const SectionHeader = ({ icon: Icon, iconColor, iconBg, title, subtitle, link }) => (
  <div style={{
    padding: '16px 20px',
    borderBottom: '1px solid var(--border-subtle)',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{ padding: 6, background: iconBg, borderRadius: 8, color: iconColor, display: 'flex' }}>
        <Icon size={15} />
      </div>
      <div>
        <div style={{ fontWeight: 700, fontSize: '.9rem' }}>{title}</div>
        {subtitle && <div style={{ marginTop: 2 }}>{subtitle}</div>}
      </div>
    </div>
    {link && (
      <Link to={link.to} style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '.78rem', color: 'var(--accent-blue)', textDecoration: 'none', fontWeight: 500 }}>
        {link.label} <ArrowRight size={12} />
      </Link>
    )}
  </div>
);

const WorkerRow = ({ worker, isLast }) => {
  const isActive = worker.status === 'ACTIVE';
  const cpuPct   = Math.round(worker.cpuUsagePercent ?? 0);
  const memPct   = worker.totalMemoryMb
    ? Math.round(((worker.usedMemoryMb ?? 0) / worker.totalMemoryMb) * 100)
    : 0;

  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '11px 20px',
      borderBottom: isLast ? 'none' : '1px solid var(--border-subtle)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div className={`health-dot ${isActive ? 'running' : 'failed'}`} />
        <div>
          <div style={{ fontWeight: 600, fontSize: '.85rem' }}>{worker.workerName}</div>
          <div style={{ fontSize: '.7rem', color: 'var(--text-muted)' }}>{worker.ipAddress}</div>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 16, alignItems: 'center' }}>
        <MiniStat icon={Cpu} value={`${cpuPct}%`} warn={cpuPct > 80} />
        <MiniStat icon={MemoryStick} value={`${memPct}%`} warn={memPct > 80} />
        <Badge variant={isActive ? 'green' : 'red'} style={{ fontSize: '.65rem' }}>
          {isActive ? 'Active' : 'Inactive'}
        </Badge>
      </div>
    </div>
  );
};

const MiniStat = ({ icon: Icon, value, warn }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '.75rem', color: warn ? 'var(--accent-red)' : 'var(--text-muted)' }}>
    <Icon size={11} />
    <span style={{ fontWeight: warn ? 600 : 400 }}>{value}</span>
  </div>
);

const AdminProjectRow = ({ project, isLast }) => {
  const isRunning = project.totalRunningContainers > 0;
  return (
    <Link
      to={`/projects/${project.id}`}
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '11px 20px',
        textDecoration: 'none',
        color: 'inherit',
        borderBottom: isLast ? 'none' : '1px solid var(--border-subtle)',
        transition: 'background var(--transition)',
      }}
      className="list-item-hover"
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div className={`health-dot ${isRunning ? 'running' : 'stopped'}`} />
        <div>
          <div style={{ fontWeight: 600, fontSize: '.85rem' }}>{project.name}</div>
          <div style={{ fontSize: '.7rem', color: 'var(--text-muted)' }}>@{project.ownerUsername}</div>
        </div>
      </div>
      <Badge variant={isRunning ? 'green' : 'gray'}>
        {isRunning ? `${project.totalRunningContainers} container` : 'Stopped'}
      </Badge>
    </Link>
  );
};

const StatusPill = ({ children, color, bg }) => (
  <span style={{
    display: 'inline-flex', alignItems: 'center', gap: 4,
    fontSize: '.65rem', fontWeight: 600,
    padding: '2px 8px', borderRadius: 100,
    background: bg, color,
    border: `1px solid ${color}40`,
  }}>
    {children}
  </span>
);

const UserAvatar = ({ username, isAdmin }) => (
  <div style={{
    width: 28, height: 28, borderRadius: '50%',
    background: isAdmin ? 'rgba(163,113,247,.18)' : 'rgba(56,139,253,.12)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontSize: '.7rem', fontWeight: 700,
    color: isAdmin ? 'var(--accent-purple)' : 'var(--accent-blue)',
    flexShrink: 0,
  }}>
    {username?.[0]?.toUpperCase()}
  </div>
);

const EmptyState = ({ message }) => (
  <div style={{ textAlign: 'center', padding: '28px 20px', color: 'var(--text-muted)', fontSize: '.85rem' }}>
    {message}
  </div>
);
