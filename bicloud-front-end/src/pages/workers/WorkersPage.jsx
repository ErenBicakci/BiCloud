import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { workerService } from '../../services/worker.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../context/AuthContext';
import { Card, Badge, Spinner } from '../../components/ui';
import { Server, WifiOff, RefreshCw, Activity, Cpu, MemoryStick, Hash, Box, Star, Clock, Wrench } from 'lucide-react';

function UsageBar({ value, color, label }) {
  const pct = Math.min(100, Math.round(value || 0));
  const barColor = pct > 85 ? 'var(--accent-red)' : pct > 70 ? 'var(--accent-yellow)' : color;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '.72rem' }}>
        <span style={{ color: 'var(--text-muted)' }}>{label}</span>
        <span style={{ color: barColor, fontWeight: 600 }}>{pct}%</span>
      </div>
      <div style={{ height: 4, background: 'var(--bg-base)', borderRadius: 3, overflow: 'hidden' }}>
        <div style={{
          height: '100%',
          width: `${pct}%`,
          background: barColor,
          borderRadius: 3,
          transition: 'width .5s ease',
        }} />
      </div>
    </div>
  );
}

export default function WorkersPage() {
  const { error, success } = useToast();
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const [workers, setWorkers]     = useState([]);
  const [loading, setLoading]     = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    try {
      const { data } = await workerService.list();
      setWorkers(data || []);
    } catch (err) {
      error(extractError(err));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [error]);

  useEffect(() => {
    load();
    const t = setInterval(() => load(true), 15000);
    return () => clearInterval(t);
  }, [load]);

  const handleToggleMaintenance = async (worker) => {
    const enable = worker.status !== 'MAINTENANCE';
    try {
      await workerService.setMaintenance(worker.workerId, enable);
      success(enable
        ? `"${worker.workerName}" put into maintenance — no new jobs will be assigned.`
        : `"${worker.workerName}" taken out of maintenance.`);
      load(true);
    } catch (err) {
      error(extractError(err));
    }
  };

  const activeCount = workers.filter(w => w.state?.status === 'ACTIVE').length;

  if (loading && workers.length === 0) return <div className="page-loader"><Spinner size="lg" /></div>;

  return (
    <div style={{ padding: '32px', maxWidth: 1200 }}>

      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 36 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
            <div style={{ padding: '5px 10px', background: 'rgba(163,113,247,.12)', border: '1px solid rgba(163,113,247,.25)', borderRadius: 8, display: 'flex', alignItems: 'center', gap: 6 }}>
              <Activity size={13} style={{ color: 'var(--accent-purple)' }} />
              <span style={{ fontSize: '.65rem', fontWeight: 700, color: 'var(--accent-purple)', textTransform: 'uppercase', letterSpacing: '.1em' }}>
                              System Management
            </span>
            </div>
          </div>
          <h1 style={{ fontSize: '1.6rem', fontWeight: 800, marginBottom: 6, letterSpacing: '-.02em' }}>
            Worker Nodes
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '.9rem' }}>
            Infrastructure server status — {workers.length} nodes, {activeCount} active.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <Badge variant="blue" style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--accent-green)', display: 'inline-block', animation: 'pulse 1.5s infinite' }} />
            Live Monitoring
          </Badge>
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => load(true)}
            disabled={refreshing}
            style={{ display: 'flex', alignItems: 'center', gap: 5 }}
          >
            <RefreshCw size={13} style={{ animation: refreshing ? 'spin .7s linear infinite' : 'none' }} />
            Refresh
          </button>
        </div>
      </div>

      {workers.length === 0 ? (
        <Card style={{ textAlign: 'center', padding: '60px 20px' }}>
          <WifiOff size={48} style={{ opacity: 0.15, marginBottom: 16, color: 'var(--text-muted)' }} />
          <p style={{ color: 'var(--text-muted)' }}>No registered worker nodes found.</p>
        </Card>
      ) : (
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
          gap: 20,
        }}>
          {workers.map(w => (
            <WorkerCard
              key={w.workerId}
              worker={w}
              isAdmin={isAdmin}
              onToggleMaintenance={() => handleToggleMaintenance(w)}
              onClick={() => navigate(`/admin/workers/${w.workerId}`)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

const WorkerCard = ({ worker, isAdmin, onToggleMaintenance, onClick }) => {
  const status        = worker.status ?? 'UNKNOWN';
  const isActive      = status === 'ACTIVE';
  const isMaintenance = status === 'MAINTENANCE';
  const cpuPct   = Math.round(worker.cpuUsagePercent ?? 0);
  const memPct   = worker.totalMemoryMb
    ? Math.round(((worker.usedMemoryMb ?? 0) / worker.totalMemoryMb) * 100)
    : 0;

  // Last heartbeat warning color
  const heartbeatMs = worker.lastHeartbeat ? Date.now() - new Date(worker.lastHeartbeat).getTime() : null;
  const heartbeatWarn = heartbeatMs !== null && heartbeatMs > 60000;

  return (
    <Card
      style={{
        transition: 'border-color var(--transition), box-shadow var(--transition)',
        cursor: 'pointer',
      }}
      onClick={onClick}
      onMouseEnter={e => { e.currentTarget.style.borderColor = isActive ? 'rgba(63,185,80,.4)' : 'rgba(248,81,73,.4)'; e.currentTarget.style.boxShadow = '0 4px 20px rgba(0,0,0,.15)'; }}
      onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border-subtle)'; e.currentTarget.style.boxShadow = 'none'; }}
    >
      {/* Card Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{
            padding: 9,
            background: isActive ? 'rgba(63,185,80,.1)' : 'rgba(248,81,73,.1)',
            borderRadius: 10,
            color: isActive ? 'var(--accent-green)' : 'var(--accent-red)',
            display: 'flex',
          }}>
            <Server size={18} />
          </div>
          <div>
            <div style={{ fontWeight: 700, fontSize: '.95rem' }}>{worker.workerName}</div>
            <div style={{ fontSize: '.7rem', color: 'var(--text-muted)', fontFamily: 'JetBrains Mono, monospace' }}>
              {worker.meshIp || worker.ipAddress || '—'}
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Badge variant={isActive ? 'green' : (isMaintenance ? 'yellow' : 'red')}>
            {isActive ? 'Active' : (isMaintenance ? 'Maintenance' : 'Inactive')}
          </Badge>
          {isAdmin && (
            <button
              onClick={(e) => { e.stopPropagation(); onToggleMaintenance(); }}
              title={isMaintenance
                ? 'Remove from maintenance — scheduler can assign new jobs'
                : 'Put into maintenance (drain) — no new jobs, existing containers keep running'}
              style={{
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                width: 26, height: 26, borderRadius: 6, cursor: 'pointer',
                border: `1px solid ${isMaintenance ? 'var(--accent-yellow)' : 'var(--border-subtle)'}`,
                background: isMaintenance ? 'rgba(210,153,34,.15)' : 'transparent',
                color: isMaintenance ? 'var(--accent-yellow)' : 'var(--text-muted)',
                transition: 'all .15s',
              }}
            >
              <Wrench size={13} />
            </button>
          )}
        </div>
      </div>

      {/* Usage Bars */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 20 }}>
        <UsageBar value={cpuPct}  color="var(--accent-blue)"  label="CPU Usage" />
        <UsageBar value={memPct}  color="var(--accent-cyan)"  label="Memory Usage" />
      </div>

      {/* Specs Grid */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: '1fr 1fr',
        gap: 8,
        padding: '12px',
        background: 'var(--bg-elevated)',
        borderRadius: 'var(--radius-md)',
        border: '1px solid var(--border-subtle)',
        marginBottom: 12,
      }}>
        <Spec icon={Cpu}        label="CPU"           value={`${worker.totalCpuCores} Cores`} />
        <Spec icon={MemoryStick} label="RAM"          value={`${worker.totalMemoryMb} MB`} />
        <Spec icon={Hash}       label="Version"      value={`v${worker.workerVersion}`} />
        <Spec icon={Server}     label="Port"          value={worker.serverPort} />
      </div>

      {/* Extra row: containers + score + heartbeat */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.72rem', color: 'var(--text-muted)' }}>
        <span>
          <Box size={11} style={{ display: 'inline', marginRight: 4, verticalAlign: 'middle' }} />
          <strong style={{ color: 'var(--text-primary)' }}>{worker.runningContainers ?? 0}</strong> container
        </span>
        <span title="Scheduler score">
          <Star size={11} style={{ display: 'inline', marginRight: 3, verticalAlign: 'middle' }} />
          {(worker.score ?? 0).toFixed(2)}
        </span>
        <span style={{ color: heartbeatWarn ? 'var(--accent-yellow)' : 'var(--text-muted)' }} title="Last heartbeat">
          <Clock size={11} style={{ display: 'inline', marginRight: 3, verticalAlign: 'middle' }} />
          {formatRelative(worker.lastHeartbeat)}
        </span>
      </div>
      {/* Registration date */}
        {worker.createdAt && (
        <div style={{ marginTop: 8, fontSize: '0.68rem', color: 'var(--text-muted)', textAlign: 'right' }}>
          Registered: {new Date(worker.createdAt).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })}
        </div>
      )}
    </Card>
  );
};

const Spec = ({ icon: Icon, label, value }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
    <Icon size={12} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
    <div>
      <div style={{ fontSize: '.6rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</div>
      <div style={{ fontSize: '.78rem', fontWeight: 600, color: 'var(--text-secondary)' }}>{value}</div>
    </div>
  </div>
);

function formatRelative(iso) {
  if (!iso) return '—';
  const diffSec = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diffSec < 60)    return `${Math.floor(diffSec)}s ago`;
  if (diffSec < 3600)  return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}
