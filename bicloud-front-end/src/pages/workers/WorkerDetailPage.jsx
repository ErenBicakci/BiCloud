import React, { useState, useEffect, useCallback } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { workerService } from '../../services/worker.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { Card, Badge, Spinner } from '../../components/ui';
import {
  Server, ChevronLeft, RefreshCw, Cpu, MemoryStick, Hash,
  Activity, Clock, Globe, Box, Star, Wifi, Calendar,
  AlertTriangle, CheckCircle2, Network,
} from 'lucide-react';

export default function WorkerDetailPage() {
  const { workerId } = useParams();
  const navigate = useNavigate();
  const { error } = useToast();

  const [worker, setWorker]     = useState(null);
  const [loading, setLoading]   = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    try {
      const { data } = await workerService.get(workerId);
      setWorker(data);
    } catch (err) {
      error(extractError(err));
      navigate('/admin/workers');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [workerId, error, navigate]);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) load();
    });
    return () => { cancelled = true; };
  }, [load]);

  // Silent background refresh every 15s
  useEffect(() => {
    const t = setInterval(() => {
      setNow(Date.now());
      load(true);
    }, 15000);
    return () => clearInterval(t);
  }, [load]);

  if (loading) return <div className="page-loader"><Spinner size="lg" /></div>;
  if (!worker) return null;

  const isActive = worker.status === 'ACTIVE';
  const cpuPct   = Math.round(worker.cpuUsagePercent ?? 0);
  const memPct   = worker.totalMemoryMb
    ? Math.round(((worker.usedMemoryMb ?? 0) / worker.totalMemoryMb) * 100)
    : 0;
  const heartbeatAgeMs = worker.lastHeartbeat ? now - new Date(worker.lastHeartbeat).getTime() : null;
  const heartbeatColor = !worker.lastHeartbeat
    ? 'var(--text-muted)'
    : (heartbeatAgeMs > 60000 ? 'var(--accent-red)' : 'var(--accent-green)');

  return (
    <div style={{ padding: '32px', maxWidth: 1100, margin: '0 auto' }}>

      {/* Breadcrumb */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 20 }}>
        <Link to="/admin" style={linkSubtle}>Admin</Link>
        <ChevronLeft size={12} style={{ transform: 'rotate(180deg)' }} />
        <Link to="/admin/workers" style={linkSubtle}>Worker Nodes</Link>
        <ChevronLeft size={12} style={{ transform: 'rotate(180deg)' }} />
        <span style={{ color: 'var(--text-primary)' }}>{worker.workerName}</span>
      </div>

      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 28 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <div style={{
            width: 52, height: 52, borderRadius: 'var(--radius-md)',
            background: isActive ? 'rgba(63,185,80,.12)' : 'rgba(248,81,73,.1)',
            border: `1px solid ${isActive ? 'rgba(63,185,80,.3)' : 'rgba(248,81,73,.3)'}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: isActive ? 'var(--accent-green)' : 'var(--accent-red)',
          }}>
            <Server size={26} />
          </div>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
              <h1 style={{ fontSize: '1.75rem', fontWeight: 800, lineHeight: 1.1 }}>{worker.workerName}</h1>
              <Badge variant={isActive ? 'green' : 'red'}>{isActive ? 'Active' : 'Inactive'}</Badge>
            </div>
            <div className="mono" style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              {worker.workerId}
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {refreshing && <Spinner />}
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

      {/* Status Banner */}
      <div style={{
        marginBottom: 28,
        padding: '14px 18px',
        borderRadius: 'var(--radius-md)',
        background: isActive ? 'rgba(63,185,80,.07)' : 'rgba(248,81,73,.07)',
        border: `1px solid ${isActive ? 'rgba(63,185,80,.25)' : 'rgba(248,81,73,.25)'}`,
        display: 'flex', alignItems: 'center', gap: 12,
      }}>
        {isActive
          ? <CheckCircle2 size={18} color="var(--accent-green)" />
          : <AlertTriangle size={18} color="var(--accent-red)" />
        }
        <div>
          <div style={{ fontWeight: 600, fontSize: '0.88rem', color: isActive ? 'var(--accent-green)' : 'var(--accent-red)' }}>
          {isActive ? 'Worker active and sending heartbeats' : 'Worker inactive — no heartbeat received'}
          </div>
          <div style={{ fontSize: '0.76rem', color: 'var(--text-muted)', marginTop: 2 }}>
            Last heartbeat: {formatRelative(worker.lastHeartbeat, now)}
          </div>
        </div>
      </div>

      {/* Resource Usage */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 14, marginBottom: 24 }}>
        <MetricCard
          icon={Cpu}
          label="CPU Usage"
          value={`${cpuPct}%`}
          sub={`${worker.totalCpuCores} cores`}
          pct={cpuPct}
          color="var(--accent-blue)"
        />
        <MetricCard
          icon={MemoryStick}
          label="Memory Usage"
          value={`${memPct}%`}
          sub={`${worker.usedMemoryMb} / ${worker.totalMemoryMb} MB`}
          pct={memPct}
          color="var(--accent-cyan)"
        />
        <StatMini icon={Box}      label="Running Containers" value={worker.runningContainers ?? 0} accent="var(--accent-purple)" />
        <StatMini icon={Star}     label="Scheduler Score"   value={(worker.score ?? 0).toFixed(3)} accent="var(--accent-yellow)" />
      </div>

      {/* Two columns: Network Info + Meta */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20, marginBottom: 24 }}>

        {/* Network Info */}
        <Card>
          <SectionTitle icon={Network}>Network Info</SectionTitle>
          <KvRow icon={Globe}   label="Advertised IP" value={worker.ipAddress || '—'} mono />
          {worker.meshIp && <KvRow icon={Wifi} label="Mesh Address" value={worker.meshIp} mono />}
          <KvRow icon={Server}  label="API Port"      value={worker.serverPort ?? '—'} mono />
        </Card>

        {/* Meta */}
        <Card>
          <SectionTitle icon={Activity}>Meta</SectionTitle>
          <KvRow icon={Hash}     label="Version"         value={`v${worker.workerVersion}`} />
          <KvRow icon={Clock}    label="Last Heartbeat"    value={formatRelative(worker.lastHeartbeat, now)}
                 valueColor={heartbeatColor} />
          <KvRow icon={Calendar} label="Registered"     value={formatDate(worker.createdAt)} />
        </Card>
      </div>

      {/* Hardware */}
      <Card>
        <SectionTitle icon={Server}>Hardware</SectionTitle>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 16, paddingTop: 4 }}>
          <HwStat label="CPU Cores" value={`${worker.totalCpuCores}`} unit="cores" />
          <HwStat label="Total Memory" value={`${worker.totalMemoryMb}`} unit="MB" />
          <HwStat label="Used"    value={`${worker.usedMemoryMb ?? 0}`} unit="MB" />
          <HwStat label="Usage %"    value={`${memPct}`} unit="%" />
        </div>
      </Card>
    </div>
  );
}

/* ─── Sub-components ──────────────────────────────────────────────────────── */

const linkSubtle = { color: 'var(--text-muted)', textDecoration: 'none' };

const SectionTitle = ({ icon: Icon, children }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.9rem', fontWeight: 700, marginBottom: 14 }}>
    {Icon && React.createElement(Icon, { size: 15, color: 'var(--text-secondary)' })} {children}
  </div>
);

const KvRow = ({ icon: Icon, label, value, valueColor, mono }) => (
  <div style={{
    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
    padding: '10px 0', borderBottom: '1px solid var(--border-subtle)',
  }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-muted)', fontSize: '0.82rem' }}>
      {Icon && React.createElement(Icon, { size: 13 })} {label}
    </div>
    <div className={mono ? 'mono' : ''} style={{ fontSize: '0.85rem', fontWeight: 600, color: valueColor || 'var(--text-primary)' }}>
      {value}
    </div>
  </div>
);

const MetricCard = ({ icon: Icon, label, value, sub, pct, color }) => {
  const barColor = pct > 85 ? 'var(--accent-red)' : pct > 70 ? 'var(--accent-yellow)' : color;
  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10 }}>
        <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 }}>{label}</span>
        {React.createElement(Icon, { size: 15, color: 'var(--text-muted)' })}
      </div>
      <div style={{ fontSize: '1.8rem', fontWeight: 800, lineHeight: 1, marginBottom: 6, color: barColor }}>{value}</div>
      <div style={{ height: 4, borderRadius: 2, background: 'var(--bg-elevated)', marginBottom: 6, overflow: 'hidden' }}>
        <div style={{ width: `${Math.min(pct, 100)}%`, height: '100%', background: barColor, borderRadius: 2, transition: 'width .5s ease' }} />
      </div>
      <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>{sub}</div>
    </Card>
  );
};

const StatMini = ({ icon: Icon, label, value, accent }) => (
  <Card>
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10 }}>
      <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 }}>{label}</span>
      {React.createElement(Icon, { size: 15, color: 'var(--text-muted)' })}
    </div>
    <div style={{ fontSize: '1.8rem', fontWeight: 800, lineHeight: 1, color: accent }}>{value}</div>
  </Card>
);

const HwStat = ({ label, value, unit }) => (
  <div style={{ padding: '10px 14px', background: 'var(--bg-elevated)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border-subtle)' }}>
    <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 4 }}>{label}</div>
    <div className="mono" style={{ fontSize: '1.05rem', fontWeight: 700 }}>
      {value} <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', fontWeight: 400 }}>{unit}</span>
    </div>
  </div>
);

function formatRelative(iso, now) {
  if (!iso) return '—';
  const diffSec = (now - new Date(iso).getTime()) / 1000;
  if (diffSec < 60)    return `${Math.floor(diffSec)}s ago`;
  if (diffSec < 3600)  return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('en-US', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    });
  } catch { return iso; }
}
