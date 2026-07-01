import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { projectService } from '../../services/project.service';
import { containerService } from '../../services/container.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { Card, Badge, Button, Spinner } from '../../components/ui';
import { Modal } from '../../components/ui/Modal';
import { ConfirmModal } from '../../components/ui/ConfirmModal';
import { LogsModal } from '../../components/ui/LogsModal';
import { Sparkline } from '../../components/ui/Sparkline';
import { ContainersPanel } from '../../components/ui/ContainersPanel';
import { EditServiceModal } from './modals/EditServiceModal';
import {
  PenLine,
  ChevronLeft,
  Play,
  Square,
  RefreshCw,
  Settings,
  Terminal,
  Trash2,
  Copy,
  Check,
  Eye,
  EyeOff,
  Activity,
  AlertTriangle,
  Box,
  Cpu,
  HardDrive,
  Network,
  Server,
  Globe,
  Tag,
  Calendar,
  Layers,
  Zap,
  ShieldAlert,
  RotateCw,
} from 'lucide-react';

export default function ServiceDetailPage() {
  const { id: projectId, serviceId } = useParams();
  const navigate = useNavigate();
  const { error, success } = useToast();

  const [project, setProject]       = useState(null);
  const [loading, setLoading]       = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const [scaleOpen, setScaleOpen]   = useState(false);
  const [editOpen, setEditOpen]     = useState(false);
  const [confirmCfg, setConfirmCfg] = useState(null); // { title, message, confirmLabel, requireText?, action }
  const [logsTarget, setLogsTarget] = useState(null);
  const [reloadKey, setReloadKey]   = useState(0); // ContainersPanel'i refresh tetikleme

  const loadProject = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    else setRefreshing(true);
    try {
      const projRes = await projectService.get(projectId);
      setProject(projRes.data);
    } catch (err) {
      error(extractError(err));
      navigate(`/projects/${projectId}`);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [projectId, error, navigate]);

  useEffect(() => { loadProject(); }, [loadProject]);

  // Auto-refresh every 5s while page is open (silently). Container panel handles its own refresh.
  useEffect(() => {
    const interval = setInterval(() => {
      loadProject(true);
      setReloadKey(k => k + 1);
    }, 5000);
    return () => clearInterval(interval);
  }, [loadProject]);

  const refreshAll = useCallback(() => {
    loadProject();
    setReloadKey(k => k + 1);
  }, [loadProject]);

  const service = useMemo(() => {
    if (!project?.images) return null;
    return project.images.find(img => String(img.id) === String(serviceId));
  }, [project, serviceId]);

  // ── Live metrics ───────────────────────────────────────────────────────────
  // Workers send measurements every 20s; page fetches every 5s (reloadKey).
  // Aggregated usage is accumulated on the client side for card sparklines.
  const serviceName = service?.serviceName;
  const [metrics, setMetrics] = useState([]);
  const [aggHistory, setAggHistory] = useState([]);

  useEffect(() => {
    if (!serviceName) return;
    let cancelled = false;
    containerService.metrics(projectId, serviceName)
      .then(res => {
        if (cancelled) return;
        const list = res.data || [];
        setMetrics(list);
        const live = list.filter(m => m.cpuPercent != null);
        if (live.length > 0) {
          const cpu = live.reduce((s, m) => s + m.cpuPercent, 0);
          const mem = live.reduce((s, m) => s + (m.memoryUsedMb || 0), 0);
          setAggHistory(prev => [...prev.slice(-59), { cpu, mem }]);
        }
      })
      .catch(() => { /* if metrics unavailable, cards show "—" */ });
    return () => { cancelled = true; };
  }, [projectId, serviceName, reloadKey]);
  // ─────────────────────────────────────────────────────────────────────────

  if (loading) return <div className="page-loader"><Spinner size="lg" /></div>;
  if (!service) {
    return (
      <div style={{ padding: 32 }}>
        <p style={{ color: 'var(--text-muted)' }}>Service not found.</p>
        <Link to={`/projects/${projectId}`}>← Back to project</Link>
      </div>
    );
  }

  // The "running" count in the top banner comes from the project summary (ImageSummary.runningReplicas).
  const runningCount = service.runningReplicas ?? 0;

  const isHealthy   = runningCount === service.desiredReplicas && service.desiredReplicas > 0;
  const isPartial   = runningCount > 0 && runningCount < service.desiredReplicas;
  const isStopped   = runningCount === 0 && service.desiredReplicas === 0;

  const inCooldown = service.consecutiveDeployFailures >= 5 && service.lastDeployFailureAt;

  // Live aggregate usage (from containers reporting metrics)
  const liveMetrics   = metrics.filter(m => m.cpuPercent != null);
  const hasMetrics    = liveMetrics.length > 0;
  const totalCpu      = liveMetrics.reduce((s, m) => s + m.cpuPercent, 0);
  const totalMem      = liveMetrics.reduce((s, m) => s + (m.memoryUsedMb || 0), 0);
  const totalCpuLimit = (service.cpuLimit ?? 0) * Math.max(runningCount, 1) * 100; // in %
  const totalMemLimit = (service.memoryLimitMb ?? 0) * Math.max(runningCount, 1);

  return (
    <div style={{ padding: '32px', maxWidth: 1280, margin: '0 auto' }}>

      {/* Breadcrumb */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 16 }}>
        <Link to="/projects" style={linkSubtle}>Projeler</Link>
        <ChevronLeft size={12} style={{ transform: 'rotate(180deg)' }} />
        <Link to={`/projects/${projectId}`} style={linkSubtle}>{project.name}</Link>
        <ChevronLeft size={12} style={{ transform: 'rotate(180deg)' }} />
        <span style={{ color: 'var(--text-primary)' }}>{service.serviceName}</span>
      </div>

      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 28 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 6 }}>
            <div style={{
              width: 48, height: 48, borderRadius: 'var(--radius-md)',
              background: 'linear-gradient(135deg, rgba(56,139,253,.15), rgba(163,113,247,.15))',
              border: '1px solid var(--border-accent)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: 'var(--accent-blue)'
            }}>
              <Layers size={24} />
            </div>
            <div>
              <h1 style={{ fontSize: '1.75rem', fontWeight: 800, lineHeight: 1.1 }}>{service.serviceName}</h1>
              <div className="mono" style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 2 }}>
                {service.imageName}
              </div>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {refreshing && <Spinner />}
          <Button variant="ghost" icon={RefreshCw} onClick={refreshAll} />
          <Button variant="ghost" icon={Settings} onClick={() => setScaleOpen(true)}>Scale</Button>
          <Button variant="ghost" icon={PenLine} onClick={() => setEditOpen(true)}>Edit</Button>
          <Button
            variant="ghost" icon={Trash2}
            style={{ color: 'var(--accent-red)' }}
            onClick={() => setConfirmCfg({
              title: 'Delete Service',
              message: <>
                <strong>{service.serviceName}</strong> service will be permanently deleted.
                All running containers will be stopped and removed, and environment variable
                definitions will also be deleted. This action cannot be undone.
              </>,
              confirmLabel: 'Delete Service',
              requireText: service.serviceName,
              action: async () => {
                try {
                  await projectService.deleteImage(service.id);
                  success(`Service "${service.serviceName}" deleted.`);
                  navigate(`/projects/${projectId}`);
                } catch (e) {
                  error(extractError(e));
                  setConfirmCfg(null);
                }
              },
            })}
          >Delete</Button>
          {inCooldown && (
            <Button variant="warning" icon={RotateCw} onClick={async () => {
              try { await projectService.resetFailures(service.id); success('Cooldown reset.'); refreshAll(); }
              catch (e) { error(extractError(e)); }
            }}>Reset Cooldown</Button>
          )}
        </div>
      </div>

      {/* Status banner */}
      <div style={{ marginBottom: 24 }}>
        <StatusBanner
          isHealthy={isHealthy}
          isPartial={isPartial}
          isStopped={isStopped}
          inCooldown={inCooldown}
          running={runningCount}
          desired={service.desiredReplicas}
          consecutiveFailures={service.consecutiveDeployFailures}
          lastFailureAt={service.lastDeployFailureAt}
        />
      </div>

      {/* Stats grid */}
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
        gap: 14, marginBottom: 28
      }}>
        <StatCard icon={Box}      label="Replicas"      value={`${runningCount}/${service.desiredReplicas}`}
                  hint={isHealthy ? 'All replicas running' : (isPartial ? 'Missing replicas' : (isStopped ? 'Service stopped' : 'Degraded state'))}
                  hintColor={isHealthy ? 'var(--text-muted)' : (isPartial ? 'var(--accent-yellow)' : 'var(--accent-red)')} />
        <StatCard icon={Cpu}      label="CPU Usage"
                  value={hasMetrics ? `${totalCpu.toFixed(1)}%` : '—'}
                  hint={hasMetrics
                    ? `limit: ${service.cpuLimit ?? '—'} core × ${runningCount} replicas`
                    : (runningCount > 0 ? 'Waiting for metrics (~20s)...' : 'No running containers')}
                  spark={<Sparkline data={aggHistory.map(p => p.cpu)} color="var(--accent-blue)"
                                    max={totalCpuLimit > 0 ? totalCpuLimit : undefined} />} />
        <StatCard icon={HardDrive} label="Memory Usage"
                  value={hasMetrics ? `${totalMem} MB` : '—'}
                  hint={hasMetrics
                    ? `limit: ${totalMemLimit} MB (${service.memoryLimitMb} MB × ${runningCount})`
                    : (runningCount > 0 ? 'Waiting for metrics (~20s)...' : 'No running containers')}
                  hintColor={hasMetrics && totalMemLimit > 0 && totalMem / totalMemLimit > 0.85 ? 'var(--accent-red)' : undefined}
                  spark={<Sparkline data={aggHistory.map(p => p.mem)} color="var(--accent-purple, #a371f7)"
                                    max={totalMemLimit > 0 ? totalMemLimit : undefined} />} />
        <StatCard icon={Network}  label="Container Port" value={service.containerPort} mono />
      </div>

      {/* Two-column layout */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20, marginBottom: 24 }}>

        {/* Endpoints panel */}
        <Card>
          <SectionTitle icon={Globe}>Service Addresses</SectionTitle>
          <EndpointRow
            label="Internal (Mesh)"
            url={`http://bicloud-gateway:9000/_bicloud/mesh/${project.name}/${service.serviceName}`}
            hint={`A single env is injected into each container: BICLOUD_MESH_BASE. To access this service: \${BICLOUD_MESH_BASE}/${service.serviceName}/<endpoint> — the same address works regardless of which machine the service runs on`}
          />
          <EndpointRow
            label="External (Gateway)"
            url={`http://${service.serviceName}.${project.name}.bicloud.local:9000`}
            hint="Host-based, load-balanced access via edge gateway (DNS/hosts entry required on client)"
          />
        </Card>

        {/* Tags / metadata */}
        <Card>
          <SectionTitle icon={Tag}>Meta</SectionTitle>
          <KvRow icon={Server} label="Service ID" value={`#${service.id}`} mono />
          <KvRow icon={Calendar} label="Created" value={formatDate(service.createdAt)} />
          <KvRow icon={Activity} label="Health"
                 value={isHealthy ? 'Healthy' : (isPartial ? 'Partial' : (isStopped ? 'Stopped' : 'Degraded'))}
                 valueColor={isHealthy ? 'var(--accent-green)' : (isPartial ? 'var(--accent-yellow)' : 'var(--accent-red)')} />
        </Card>
      </div>

      {/* Environment Variables */}
      <Card style={{ marginBottom: 24 }}>
        <EnvVarsSection envVars={service.environmentVariables || {}} />
      </Card>

      {/* Container Instances */}
      <ContainersPanel
        projectId={projectId}
        serviceName={service.serviceName}
        reloadKey={reloadKey}
        onLogs={setLogsTarget}
        onStop={async (id) => {
          try { await containerService.stop(id); success('Container stopped.'); refreshAll(); }
          catch (e) { error(extractError(e)); }
        }}
        onRemove={(id) => setConfirmCfg({
          title: 'Delete Container',
          message: 'This container will be stopped and removed. The target replica count of the service has not changed, so self-healing may create a new one.',
          confirmLabel: 'Delete',
          action: async () => {
            try { await containerService.remove(id); success('Container deleted.'); refreshAll(); }
            catch (e) { error(extractError(e)); }
            setConfirmCfg(null);
          },
        })}
      />

      {/* Modals */}
      {scaleOpen && (
        <ScaleModal
          image={service}
          onClose={() => setScaleOpen(false)}
          onSuccess={refreshAll}
        />
      )}
      <LogsModal instance={logsTarget} onClose={() => setLogsTarget(null)} />

      <EditServiceModal
        service={service}
        isOpen={editOpen}
        onClose={() => setEditOpen(false)}
        onSuccess={refreshAll}
      />

      <ConfirmModal
        isOpen={!!confirmCfg}
        title={confirmCfg?.title}
        message={confirmCfg?.message}
        confirmLabel={confirmCfg?.confirmLabel}
        requireText={confirmCfg?.requireText}
        onConfirm={() => confirmCfg?.action()}
        onClose={() => setConfirmCfg(null)}
      />
    </div>
  );
}

const linkSubtle = { color: 'var(--text-muted)', textDecoration: 'none' };

const StatusBanner = ({ isHealthy, isPartial, isStopped, inCooldown, running, desired, consecutiveFailures, lastFailureAt }) => {
  let bg, border, color, icon, text, sub;
  if (inCooldown) {
    bg = 'rgba(248,81,73,.08)'; border = 'rgba(248,81,73,.3)'; color = 'var(--accent-red)';
    icon = ShieldAlert;
    text = `Self-healing disabled — ${consecutiveFailures} failed deploy attempts`;
    sub  = `Last error: ${formatRelative(lastFailureAt)}. 5-minute cooldown active. Use "Reset Cooldown" to recover manually.`;
  } else if (isHealthy) {
    bg = 'rgba(63,185,80,.08)'; border = 'rgba(63,185,80,.3)'; color = 'var(--accent-green)';
    icon = Activity;
    text = 'Service healthy'; sub = `${running}/${desired} replicas running.`;
  } else if (isPartial) {
    bg = 'rgba(210,153,34,.08)'; border = 'rgba(210,153,34,.3)'; color = 'var(--accent-yellow)';
    icon = AlertTriangle;
    text = 'Partially running'; sub = `${running}/${desired} replicas up. Self-healing is trying to restore the missing ones.`;
  } else if (isStopped) {
    bg = 'rgba(139,148,158,.06)'; border = 'rgba(139,148,158,.2)'; color = 'var(--text-muted)';
    icon = Square;
    text = 'Service stopped'; sub = 'Replica count is 0. Scale up to start.';
  } else {
    bg = 'rgba(248,81,73,.08)'; border = 'rgba(248,81,73,.3)'; color = 'var(--accent-red)';
    icon = AlertTriangle;
    text = 'Service not running'; sub = `${running}/${desired} replicas up.`;
  }
  const Icon = icon;
  return (
    <div style={{
      background: bg, border: `1px solid ${border}`,
      borderRadius: 'var(--radius-md)', padding: '14px 18px',
      display: 'flex', alignItems: 'center', gap: 14
    }}>
      <Icon size={22} color={color} />
      <div>
        <div style={{ fontWeight: 600, color, fontSize: '0.9rem' }}>{text}</div>
        <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: 2 }}>{sub}</div>
      </div>
    </div>
  );
};

const StatCard = ({ icon: Icon, label, value, hint, hintColor, mono, spark }) => (
  <Card>
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 10 }}>
      <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 }}>
        {label}
      </span>
      <Icon size={16} color="var(--text-muted)" />
    </div>
    <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 8 }}>
      <div className={mono ? 'mono' : ''} style={{ fontSize: '1.4rem', fontWeight: 700, lineHeight: 1.1 }}>
        {value}
      </div>
      {spark && <div style={{ flexShrink: 0 }}>{spark}</div>}
    </div>
    {hint && (
      <div style={{ fontSize: '0.7rem', color: hintColor || 'var(--text-muted)', marginTop: 4 }}>
        {hint}
      </div>
    )}
  </Card>
);

const SectionTitle = ({ icon: Icon, children, noMargin }) => (
  <div style={{
    display: 'flex', alignItems: 'center', gap: 8,
    fontSize: '0.95rem', fontWeight: 700, marginBottom: noMargin ? 0 : 14
  }}>
    {Icon && <Icon size={16} color="var(--text-secondary)" />} {children}
  </div>
);

const EndpointRow = ({ label, url, hint }) => {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    navigator.clipboard.writeText(url);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };
  return (
    <div style={{ marginBottom: 12 }}>
      <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: 4 }}>{label}</div>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        padding: '8px 10px', background: 'var(--bg-elevated)',
        border: '1px solid var(--border-subtle)', borderRadius: 'var(--radius-sm)'
      }}>
        <code className="mono" style={{ flex: 1, fontSize: '0.78rem', color: 'var(--accent-cyan)' }}>{url}</code>
        <button onClick={handleCopy} style={iconBtnStyle} title="Copy">
          {copied ? <Check size={14} color="var(--accent-green)" /> : <Copy size={14} />}
        </button>
      </div>
      {hint && <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 4 }}>{hint}</div>}
    </div>
  );
};

const KvRow = ({ icon: Icon, label, value, valueColor, mono }) => (
  <div style={{
    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
    padding: '10px 0', borderBottom: '1px solid var(--border-subtle)'
  }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-muted)', fontSize: '0.82rem' }}>
      {Icon && <Icon size={14} />} {label}
    </div>
    <div className={mono ? 'mono' : ''} style={{ fontSize: '0.85rem', fontWeight: 600, color: valueColor || 'var(--text-primary)' }}>
      {value}
    </div>
  </div>
);

const EnvVarsSection = ({ envVars }) => {
  const [showValues, setShowValues] = useState(false);
  const [copiedKey, setCopiedKey] = useState(null);
  const entries = Object.entries(envVars);

  const handleCopy = (key, value) => {
    navigator.clipboard.writeText(value);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 1500);
  };

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
        <SectionTitle icon={Zap} noMargin>
          Environment Variables
          <span style={{ marginLeft: 10, color: 'var(--text-muted)', fontWeight: 500, fontSize: '0.75rem' }}>
            ({entries.length})
          </span>
        </SectionTitle>
        {entries.length > 0 && (
          <Button size="sm" variant="ghost" icon={showValues ? EyeOff : Eye} onClick={() => setShowValues(!showValues)}>
            {showValues ? 'Hide' : 'Show'}
          </Button>
        )}
      </div>
      {entries.length === 0 ? (
        <div style={{ padding: 20, textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.82rem' }}>
          No environment variables defined for this service.
        </div>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th style={{ width: '35%' }}>Key</th>
                <th>Value</th>
                <th style={{ width: 60 }}></th>
              </tr>
            </thead>
            <tbody>
              {entries.map(([key, value]) => (
                <tr key={key}>
                  <td className="mono" style={{ color: 'var(--accent-cyan)', fontSize: '0.8rem' }}>{key}</td>
                  <td className="mono" style={{ fontSize: '0.8rem', wordBreak: 'break-all' }}>
                    {showValues ? value : '•'.repeat(Math.min(value?.length || 8, 24))}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <button onClick={() => handleCopy(key, value)} style={iconBtnStyle} title="Copy value">
                      {copiedKey === key ? <Check size={14} color="var(--accent-green)" /> : <Copy size={14} />}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
};



const ScaleModal = ({ image, onClose, onSuccess }) => {
  const { error, success } = useToast();
  const [replicas, setReplicas] = useState(image.desiredReplicas);
  const [loading, setLoading]   = useState(false);

  const handleScale = async () => {
    setLoading(true);
    try {
      await projectService.scale(image.id, replicas);
      success('Scaling successful.');
      onSuccess();
      onClose();
    } catch (err) { error(extractError(err)); } finally { setLoading(false); }
  };

  return (
    <Modal isOpen onClose={onClose} title={`Scale Service: ${image.serviceName}`}>
      <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: 18 }}>
        Current: <strong>{image.runningReplicas}/{image.desiredReplicas}</strong> replicas.
        Set the new target count.
      </p>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24 }}>
        <input
          type="range" min="0" max="10" value={replicas}
          onChange={(e) => setReplicas(parseInt(e.target.value))}
          style={{ flex: 1 }}
        />
        <div style={{ fontSize: '1.6rem', fontWeight: 800, width: 50, textAlign: 'center' }}>{replicas}</div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={handleScale} loading={loading} icon={Play}>Apply</Button>
      </div>
    </Modal>
  );
};




const iconBtnStyle = {
  background: 'transparent', border: 'none', cursor: 'pointer',
  color: 'var(--text-muted)', padding: 4, borderRadius: 4,
  display: 'inline-flex', alignItems: 'center', transition: 'color .15s'
};

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('en-US', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit'
    });
  } catch { return iso; }
}

function formatRelative(iso) {
  if (!iso) return '—';
  const date = new Date(iso);
  const diffSec = (Date.now() - date.getTime()) / 1000;
  if (diffSec < 60)     return `${Math.floor(diffSec)}s ago`;
  if (diffSec < 3600)   return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400)  return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

