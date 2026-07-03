import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { workerService } from '../../services/worker.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../context/AuthContext';
import { Badge, Button, Spinner } from '../../components/ui';
import {
  Activity,
  Box,
  Clock,
  Cpu,
  Hash,
  MemoryStick,
  Network,
  RefreshCw,
  Server,
  ShieldCheck,
  Star,
  WifiOff,
  Wrench,
} from 'lucide-react';

export default function WorkersPage() {
  const { error, success } = useToast();
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const [workers, setWorkers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [now, setNow] = useState(() => Date.now());

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
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) load();
    });
    const t = setInterval(() => load(true), 15000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [load]);

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 15000);
    return () => clearInterval(t);
  }, []);

  const handleToggleMaintenance = async (worker) => {
    const enable = worker.status !== 'MAINTENANCE';
    try {
      await workerService.setMaintenance(worker.workerId, enable);
      success(enable
        ? `"${worker.workerName}" entered maintenance. New workloads will not be scheduled there.`
        : `"${worker.workerName}" left maintenance.`);
      load(true);
    } catch (err) {
      error(extractError(err));
    }
  };

  const activeCount = workers.filter(w => w.status === 'ACTIVE').length;
  const maintenanceCount = workers.filter(w => w.status === 'MAINTENANCE').length;
  const runningContainers = workers.reduce((s, w) => s + (w.runningContainers || 0), 0);
  const totalCpu = workers.reduce((s, w) => s + (w.totalCpuCores || 0), 0);
  const totalMemory = workers.reduce((s, w) => s + (w.totalMemoryMb || 0), 0);

  if (loading && workers.length === 0) return <div className="page-loader"><Spinner size="lg" /></div>;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <div className="page-kicker"><Activity size={14} /> Infrastructure</div>
          <h1 className="page-title">Worker nodes</h1>
          <p className="page-subtitle">
            Inspect machine capacity, runtime load, network identity, heartbeat freshness, and scheduler state.
          </p>
        </div>
        <div className="page-actions">
          <Badge variant="blue">
            <span className="health-dot running" style={{ width: 6, height: 6 }} />
            Live
          </Badge>
          <Button variant="ghost" icon={RefreshCw} onClick={() => load(true)} disabled={refreshing}>
            {refreshing ? 'Refreshing' : 'Refresh'}
          </Button>
        </div>
      </header>

      <section className="console-grid metrics worker-summary">
        <MetricTile label="Workers" value={workers.length} note={`${activeCount} active`} icon={Server} />
        <MetricTile label="Fleet CPU" value={totalCpu} note="registered cores" icon={Cpu} />
        <MetricTile label="Fleet Memory" value={formatMemory(totalMemory)} note="registered capacity" icon={MemoryStick} />
        <MetricTile label="Containers" value={runningContainers} note={`${maintenanceCount} workers in maintenance`} icon={Box} />
      </section>

      <section className="worker-inventory">
        <div className="worker-inventory-head">
          <div>
            <div className="panel-title"><Server size={16} /> Node inventory</div>
            <div className="panel-subtitle">{workers.length} registered worker nodes</div>
          </div>
          <div className="status-strip">
            <Clock size={14} />
            Auto-refresh every 15 seconds
          </div>
        </div>

        {workers.length === 0 ? (
          <div className="empty-state">
            <WifiOff size={42} />
            <p>No registered worker nodes found.</p>
          </div>
        ) : (
          <div className="worker-card-list">
            {workers.map(w => (
              <WorkerCard
                key={w.workerId}
                worker={w}
                now={now}
                isAdmin={isAdmin}
                onToggleMaintenance={() => handleToggleMaintenance(w)}
                onOpen={() => navigate(`/admin/workers/${w.workerId}`)}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

const WorkerCard = ({ worker, now, isAdmin, onToggleMaintenance, onOpen }) => {
  const status = worker.status ?? 'UNKNOWN';
  const isActive = status === 'ACTIVE';
  const isMaintenance = status === 'MAINTENANCE';
  const cpuPct = clamp(Math.round(worker.cpuUsagePercent ?? 0));
  const memPct = worker.totalMemoryMb
    ? clamp(Math.round(((worker.usedMemoryMb ?? 0) / worker.totalMemoryMb) * 100))
    : 0;
  const heartbeatMs = worker.lastHeartbeat ? now - new Date(worker.lastHeartbeat).getTime() : null;
  const heartbeatWarn = heartbeatMs !== null && heartbeatMs > 60000;
  const statusTone = getStatusTone(status);

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onOpen();
    }
  };

  return (
    <article
      className={`worker-card worker-card-${statusTone}`}
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={handleKeyDown}
    >
      <div className="worker-card-main">
        <div className="worker-node-icon">
          <Server size={22} />
          <span className={`health-dot ${isActive ? 'running' : (isMaintenance ? 'stopped' : 'failed')}`} />
        </div>
        <div className="worker-node-copy">
          <div className="worker-node-topline">
            <h2>{worker.workerName}</h2>
            <Badge variant={getBadgeVariant(status)}>{formatStatus(status)}</Badge>
          </div>
          <div className="worker-node-meta">
            <span className="mono"><Hash size={12} /> {shortId(worker.workerId)}</span>
            <span>v{worker.workerVersion || 'unknown'}</span>
            <span className={heartbeatWarn ? 'warn' : ''}>
              <Clock size={12} /> {formatRelative(worker.lastHeartbeat, now)}
            </span>
          </div>
        </div>
      </div>

      <div className="worker-card-resources">
        <ResourceMeter
          icon={Cpu}
          label="CPU"
          value={`${cpuPct}%`}
          detail={`${worker.totalCpuCores || 0} cores`}
          pct={cpuPct}
          warn={cpuPct > 85}
        />
        <ResourceMeter
          icon={MemoryStick}
          label="Memory"
          value={`${memPct}%`}
          detail={`${formatMemory(worker.usedMemoryMb || 0)} / ${formatMemory(worker.totalMemoryMb || 0)}`}
          pct={memPct}
          warn={memPct > 85}
        />
        <WorkerFact icon={Box} label="Containers" value={worker.runningContainers ?? 0} />
        <WorkerFact icon={Star} label="Score" value={(worker.score ?? 0).toFixed(2)} />
      </div>

      <div className="worker-card-network">
        <NetworkFact label="Node IP" value={worker.ipAddress || '-'} />
        <NetworkFact label="Mesh IP" value={worker.meshIp || '-'} />
        <NetworkFact label="Port" value={worker.serverPort ?? '-'} />
      </div>

      <div className="worker-card-actions">
        <Button size="sm" variant="ghost" icon={ShieldCheck} onClick={(e) => {
          e.stopPropagation();
          onOpen();
        }}>
          Details
        </Button>
        {isAdmin && (
          <Button
            size="sm"
            variant={isMaintenance ? 'warning' : 'ghost'}
            icon={Wrench}
            onClick={(e) => {
              e.stopPropagation();
              onToggleMaintenance();
            }}
          >
            {isMaintenance ? 'Resume' : 'Drain'}
          </Button>
        )}
      </div>
    </article>
  );
};

const MetricTile = ({ label, value, note, icon: Icon }) => (
  <div className="metric-tile">
    <div className="metric-topline">
      <div className="metric-name">{label}</div>
      <div className="icon-box">{React.createElement(Icon, { size: 16 })}</div>
    </div>
    <div>
      <div className="metric-number" style={{ fontSize: typeof value === 'string' ? '1.35rem' : undefined }}>{value}</div>
      <div className="metric-note">{note}</div>
    </div>
  </div>
);

const ResourceMeter = ({ icon: Icon, label, value, detail, pct, warn }) => (
  <div className="worker-resource-meter">
    <div className="worker-resource-head">
      <span>{React.createElement(Icon, { size: 14 })} {label}</span>
      <strong className={warn ? 'warn' : ''}>{value}</strong>
    </div>
    <div className="usage-meter">
      <span
        style={{
          '--usage-value': `${pct}%`,
          '--usage-color': warn ? 'var(--accent-red)' : 'var(--accent-blue)',
        }}
      />
    </div>
    <div className="worker-resource-detail">{detail}</div>
  </div>
);

const WorkerFact = ({ icon: Icon, label, value }) => (
  <div className="worker-fact">
    <span>{React.createElement(Icon, { size: 14 })} {label}</span>
    <strong>{value}</strong>
  </div>
);

const NetworkFact = ({ label, value }) => (
  <div className="worker-network-fact">
    <span>{label}</span>
    <strong className="mono">{value}</strong>
  </div>
);

function getBadgeVariant(status) {
  if (status === 'ACTIVE') return 'green';
  if (status === 'MAINTENANCE') return 'yellow';
  if (status === 'OVERLOADED') return 'yellow';
  return 'red';
}

function getStatusTone(status) {
  if (status === 'ACTIVE') return 'active';
  if (status === 'MAINTENANCE') return 'maintenance';
  return 'inactive';
}

function formatStatus(status) {
  if (status === 'ACTIVE') return 'Active';
  if (status === 'MAINTENANCE') return 'Maintenance';
  if (status === 'OVERLOADED') return 'Overloaded';
  return 'Inactive';
}

function clamp(value) {
  return Math.min(Math.max(value, 0), 100);
}

function shortId(id) {
  if (!id) return 'unknown';
  return String(id).slice(0, 8);
}

function formatMemory(mb) {
  const value = Number(mb || 0);
  if (value >= 1024) return `${(value / 1024).toFixed(value >= 10240 ? 0 : 1)} GB`;
  return `${value} MB`;
}

function formatRelative(iso, now) {
  if (!iso) return 'no heartbeat';
  const diffSec = Math.max(0, (now - new Date(iso).getTime()) / 1000);
  if (diffSec < 60) return `${Math.floor(diffSec)}s ago`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}
