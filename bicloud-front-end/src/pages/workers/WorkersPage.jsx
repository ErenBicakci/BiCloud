import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { workerService } from '../../services/worker.service';
import { projectService } from '../../services/project.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../context/AuthContext';
import { Badge, Button, Spinner } from '../../components/ui';
import { ConfirmModal } from '../../components/ui/ConfirmModal';
import { ClusterTopologyMap } from '../../components/ui/ClusterTopologyMap';
import {
  Activity,
  Box,
  Clock,
  Cpu,
  Filter,
  Grid,
  Hash,
  Layers,
  LayoutGrid,
  MemoryStick,
  Network,
  Radio,
  RefreshCw,
  Search,
  Server,
  ShieldCheck,
  Star,
  WifiOff,
  Wrench,
  X,
} from 'lucide-react';

export default function WorkersPage() {
  const { error, success } = useToast();
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const [workers, setWorkers] = useState([]);
  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [viewMode, setViewMode] = useState('cards'); // 'cards' | 'topology'
  const [statusFilter, setStatusFilter] = useState('ALL'); // 'ALL' | 'ACTIVE' | 'MAINTENANCE' | 'OFFLINE'
  const [searchQuery, setSearchQuery] = useState('');
  const [confirmDrain, setConfirmDrain] = useState(null);
  const [now, setNow] = useState(() => Date.now());

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    try {
      const [wRes, pRes] = await Promise.all([
        workerService.list(),
        projectService.list(),
      ]);
      setWorkers(wRes.data || []);
      setProjects(pRes.data || []);
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
        ? `Worker "${worker.workerName}" is now in Maintenance (drained from new allocations).`
        : `Worker "${worker.workerName}" returned to ACTIVE status.`);
      load(true);
      setConfirmDrain(null);
    } catch (err) {
      error(extractError(err));
    }
  };

  const filteredWorkers = useMemo(() => {
    return workers.filter(w => {
      const matchesStatus =
        statusFilter === 'ALL' ||
        (statusFilter === 'ACTIVE' && w.status === 'ACTIVE') ||
        (statusFilter === 'MAINTENANCE' && w.status === 'MAINTENANCE') ||
        (statusFilter === 'OFFLINE' && w.status !== 'ACTIVE' && w.status !== 'MAINTENANCE');

      const q = searchQuery.toLowerCase().trim();
      const matchesSearch =
        !q ||
        w.workerName.toLowerCase().includes(q) ||
        w.ipAddress?.includes(q) ||
        w.workerVersion?.toLowerCase().includes(q);

      return matchesStatus && matchesSearch;
    });
  }, [workers, statusFilter, searchQuery]);

  const activeCount = workers.filter(w => w.status === 'ACTIVE').length;
  const maintenanceCount = workers.filter(w => w.status === 'MAINTENANCE').length;
  const offlineCount = workers.length - activeCount - maintenanceCount;
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
          <div className="segmented" aria-label="View Mode">
            <button
              type="button"
              className={viewMode === 'cards' ? 'active' : ''}
              onClick={() => setViewMode('cards')}
            >
              <LayoutGrid size={13} /> Cards
            </button>
            <button
              type="button"
              className={viewMode === 'topology' ? 'active' : ''}
              onClick={() => setViewMode('topology')}
            >
              <Radio size={13} /> Topology Map
            </button>
          </div>

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
        <MetricTile label="Workers" value={workers.length} note={`${activeCount} active, ${maintenanceCount} maintenance`} icon={Server} />
        <MetricTile label="Fleet CPU" value={totalCpu} note="registered cores" icon={Cpu} />
        <MetricTile label="Fleet Memory" value={formatMemory(totalMemory)} note="registered capacity" icon={MemoryStick} />
        <MetricTile label="Containers" value={runningContainers} note={`${runningContainers} active across fleet`} icon={Box} />
      </section>

      {viewMode === 'topology' ? (
        <section style={{ marginTop: 24 }}>
          <ClusterTopologyMap workers={workers} projects={projects} />
        </section>
      ) : (
        <section className="worker-inventory">
          <div className="worker-inventory-head">
            <div>
              <div className="panel-title"><Server size={16} /> Node inventory</div>
              <div className="panel-subtitle">{workers.length} registered worker nodes</div>
            </div>

            {/* Filter toolbar */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <div className="log-search-wrap">
                <Search size={13} className="log-search-icon" />
                <input
                  type="text"
                  placeholder="Filter nodes..."
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  className="input input-sm log-search-input"
                />
                {searchQuery && (
                  <button
                    type="button"
                    className="btn-icon log-search-clear"
                    onClick={() => setSearchQuery('')}
                    aria-label="Clear filter"
                  >
                    <X size={13} />
                  </button>
                )}
              </div>

              <div className="segmented">
                <button
                  type="button"
                  className={statusFilter === 'ALL' ? 'active' : ''}
                  onClick={() => setStatusFilter('ALL')}
                >
                  All ({workers.length})
                </button>
                <button
                  type="button"
                  className={statusFilter === 'ACTIVE' ? 'active' : ''}
                  onClick={() => setStatusFilter('ACTIVE')}
                >
                  Active ({activeCount})
                </button>
                <button
                  type="button"
                  className={statusFilter === 'MAINTENANCE' ? 'active' : ''}
                  onClick={() => setStatusFilter('MAINTENANCE')}
                >
                  Maintenance ({maintenanceCount})
                </button>
                {offlineCount > 0 && (
                  <button
                    type="button"
                    className={statusFilter === 'OFFLINE' ? 'active' : ''}
                    onClick={() => setStatusFilter('OFFLINE')}
                  >
                    Offline ({offlineCount})
                  </button>
                )}
              </div>
            </div>
          </div>

          {filteredWorkers.length === 0 ? (
            <div className="empty-state">
              <WifiOff size={42} />
              <p>No worker nodes matching current filter.</p>
            </div>
          ) : (
            <div className="worker-card-list">
              {filteredWorkers.map(w => (
                <WorkerCard
                  key={w.workerId}
                  worker={w}
                  now={now}
                  isAdmin={isAdmin}
                  onToggleMaintenance={() => {
                    if (w.status !== 'MAINTENANCE') {
                      setConfirmDrain({
                        worker: w,
                        title: `Drain Worker: ${w.workerName}`,
                        message: `Putting "${w.workerName}" in Maintenance stops new container allocations from being scheduled on this machine. Existing containers will finish or can be migrated.`,
                      });
                    } else {
                      handleToggleMaintenance(w);
                    }
                  }}
                  onOpen={() => navigate(`/admin/workers/${w.workerId}`)}
                />
              ))}
            </div>
          )}
        </section>
      )}

      {/* Confirmation Modal for Draining */}
      {confirmDrain && (
        <ConfirmModal
          isOpen
          title={confirmDrain.title}
          message={confirmDrain.message}
          confirmLabel="Enable Maintenance (Drain)"
          onConfirm={() => handleToggleMaintenance(confirmDrain.worker)}
          onCancel={() => setConfirmDrain(null)}
        />
      )}
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
        <NetworkFact label="Advertised IP" value={worker.ipAddress || '-'} />
        <NetworkFact label="API Port" value={worker.serverPort ?? '-'} />
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
