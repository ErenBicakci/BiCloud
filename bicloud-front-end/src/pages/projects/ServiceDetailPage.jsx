import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { projectService } from '../../services/project.service';
import { containerService } from '../../services/container.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { Badge, Button, Spinner } from '../../components/ui';
import { Modal } from '../../components/ui/Modal';
import { ConfirmModal } from '../../components/ui/ConfirmModal';
import { LogsModal } from '../../components/ui/LogsModal';
import { Sparkline } from '../../components/ui/Sparkline';
import { ContainersPanel } from '../../components/ui/ContainersPanel';
import { EditServiceModal } from './modals/EditServiceModal';
import {
  Activity,
  AlertTriangle,
  Box,
  Calendar,
  Check,
  ChevronRight,
  Copy,
  Cpu,
  Eye,
  EyeOff,
  Globe,
  HardDrive,
  Layers,
  Network,
  PenLine,
  Play,
  RefreshCw,
  RotateCw,
  Server,
  Settings,
  ShieldAlert,
  Square,
  Tag,
  Trash2,
  Zap,
} from 'lucide-react';

export default function ServiceDetailPage() {
  const { id: projectId, serviceId } = useParams();
  const navigate = useNavigate();
  const { error, success } = useToast();

  const [project, setProject] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [scaleOpen, setScaleOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [confirmCfg, setConfirmCfg] = useState(null);
  const [logsTarget, setLogsTarget] = useState(null);
  const [reloadKey, setReloadKey] = useState(0);

  const loadProject = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true);
    else setLoading(true);
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

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) loadProject();
    });
    return () => { cancelled = true; };
  }, [loadProject]);

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

  const serviceName = service?.serviceName;
  const [metrics, setMetrics] = useState([]);
  const [aggHistory, setAggHistory] = useState([]);

  useEffect(() => {
    if (!serviceName) return undefined;
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
      .catch(() => {});
    return () => { cancelled = true; };
  }, [projectId, serviceName, reloadKey]);

  if (loading) return <div className="page-loader"><Spinner size="lg" /></div>;
  if (!service) {
    return (
      <div className="service-shell">
        <p style={{ color: 'var(--text-muted)' }}>Service not found.</p>
        <Link to={`/projects/${projectId}`}>Back to project</Link>
      </div>
    );
  }

  const runningCount = service.runningReplicas ?? 0;
  const isHealthy = runningCount === service.desiredReplicas && service.desiredReplicas > 0;
  const isPartial = runningCount > 0 && runningCount < service.desiredReplicas;
  const isStopped = runningCount === 0 && service.desiredReplicas === 0;
  const inCooldown = service.consecutiveDeployFailures >= 5 && service.lastDeployFailureAt;

  const liveMetrics = metrics.filter(m => m.cpuPercent != null);
  const hasMetrics = liveMetrics.length > 0;
  const totalCpu = liveMetrics.reduce((s, m) => s + m.cpuPercent, 0);
  const totalMem = liveMetrics.reduce((s, m) => s + (m.memoryUsedMb || 0), 0);
  const totalCpuLimit = (service.cpuLimit ?? 0) * Math.max(runningCount, 1) * 100;
  const totalMemLimit = (service.memoryLimitMb ?? 0) * Math.max(runningCount, 1);

  return (
    <div className="service-shell">
      <nav className="crumbs">
        <Link to="/projects">Projects</Link>
        <ChevronRight size={12} />
        <Link to={`/projects/${projectId}`}>{project.name}</Link>
        <ChevronRight size={12} />
        <span style={{ color: 'var(--text-primary)' }}>{service.serviceName}</span>
      </nav>

      <section className="hero-panel service-hero">
        <div className="service-heading">
          <div className="service-mark">
            <Layers size={25} />
          </div>
          <div style={{ minWidth: 0 }}>
            <div className="page-kicker" style={{ marginBottom: 5 }}>Service</div>
            <h1 className="hero-title">{service.serviceName}</h1>
            <div className="mono truncate" style={{ color: 'var(--text-muted)', fontSize: '.82rem', marginTop: 4 }}>
              {service.imageName}
            </div>
          </div>
        </div>

        <div className="service-actions">
          {refreshing && <Spinner />}
          <Button variant="ghost" icon={RefreshCw} onClick={refreshAll} />
          <Button variant="ghost" icon={Settings} onClick={() => setScaleOpen(true)}>Scale</Button>
          <Button variant="ghost" icon={PenLine} onClick={() => setEditOpen(true)}>Edit</Button>
          <Button
            variant="ghost"
            icon={Trash2}
            style={{ color: 'var(--accent-red)' }}
            onClick={() => setConfirmCfg({
              title: 'Delete Service',
              message: (
                <>
                  <strong>{service.serviceName}</strong> service will be permanently deleted. All running
                  containers and environment variable definitions will also be removed.
                </>
              ),
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
          >
            Delete
          </Button>
          {inCooldown && (
            <Button variant="warning" icon={RotateCw} onClick={async () => {
              try {
                await projectService.resetFailures(service.id);
                success('Cooldown reset.');
                refreshAll();
              } catch (e) {
                error(extractError(e));
              }
            }}>
              Reset Cooldown
            </Button>
          )}
        </div>
      </section>

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

      <section className="stat-grid" style={{ marginTop: 18 }}>
        <StatCard
          icon={Box}
          label="Replicas"
          value={`${runningCount}/${service.desiredReplicas}`}
          hint={isHealthy ? 'All replicas running' : (isPartial ? 'Missing replicas' : (isStopped ? 'Service stopped' : 'Degraded state'))}
          tone={isHealthy ? 'green' : (isPartial ? 'yellow' : 'red')}
        />
        <StatCard
          icon={Cpu}
          label="CPU usage"
          value={hasMetrics ? `${totalCpu.toFixed(1)}%` : '-'}
          hint={hasMetrics ? `limit: ${service.cpuLimit ?? '-'} core x ${runningCount} replicas` : (runningCount > 0 ? 'Waiting for metrics' : 'No running containers')}
          spark={<Sparkline data={aggHistory.map(p => p.cpu)} color="var(--accent-blue)" max={totalCpuLimit > 0 ? totalCpuLimit : undefined} />}
        />
        <StatCard
          icon={HardDrive}
          label="Memory usage"
          value={hasMetrics ? `${totalMem} MB` : '-'}
          hint={hasMetrics ? `limit: ${totalMemLimit} MB (${service.memoryLimitMb} MB x ${runningCount})` : (runningCount > 0 ? 'Waiting for metrics' : 'No running containers')}
          tone={hasMetrics && totalMemLimit > 0 && totalMem / totalMemLimit > 0.85 ? 'red' : undefined}
          spark={<Sparkline data={aggHistory.map(p => p.mem)} color="var(--accent-purple)" max={totalMemLimit > 0 ? totalMemLimit : undefined} />}
        />
        <StatCard icon={Network} label="Container port" value={service.containerPort} mono />
      </section>

      <section className="service-grid">
        <SectionCard title="Service access" icon={Globe} subtitle="How containers and external clients reach this service">
          <div className="endpoint-list">
            <EndpointRow
              label="Inside project mesh"
              badge="Always available"
              description="Every deployed container receives this env automatically. Services in the same project call each other through the gateway mesh path."
              lines={[
                {
                  label: 'Injected env',
                  value: `BICLOUD_MESH_BASE=http://bicloud-gateway:9000/_bicloud/mesh/${project.name}`,
                },
                {
                  label: 'Call pattern',
                  value: `$BICLOUD_MESH_BASE/${service.serviceName}/<path>`,
                },
              ]}
            />
            <EndpointRow
              label="Outside the mesh"
              badge={service.exposeExternally ? 'Enabled' : 'Disabled'}
              disabled={!service.exposeExternally}
              description={service.exposeExternally
                ? 'External clients must resolve this host to a BiCloud gateway. The gateway reads the Host header and load-balances to running instances.'
                : 'External Host-based routing is blocked. Internal mesh traffic above still works.'}
              lines={[
                {
                  label: 'Gateway host',
                  value: service.exposeExternally
                    ? `http://${service.serviceName}.${project.name}.bicloud.local:9000/<path>`
                    : 'External access disabled',
                },
              ]}
            />
          </div>
        </SectionCard>

        <SectionCard title="Runtime metadata" icon={Tag} subtitle="Identity, routing and health state">
          <div className="kv-list">
            <KvRow icon={Server} label="Service ID" value={`#${service.id}`} mono />
            <KvRow icon={Calendar} label="Created" value={formatDate(service.createdAt)} />
            <KvRow
              icon={Globe}
              label="Internet egress"
              value={service.allowInternet ? 'Allowed by admin' : 'Isolated'}
              valueColor={service.allowInternet ? 'var(--accent-yellow)' : 'var(--accent-green)'}
            />
            <KvRow
              icon={Network}
              label="External access"
              value={service.exposeExternally ? 'Exposed through gateway' : 'Internal mesh only'}
              valueColor={service.exposeExternally ? 'var(--accent-yellow)' : 'var(--accent-green)'}
            />
            <KvRow
              icon={Activity}
              label="Health"
              value={isHealthy ? 'Healthy' : (isPartial ? 'Partial' : (isStopped ? 'Stopped' : 'Degraded'))}
              valueColor={isHealthy ? 'var(--accent-green)' : (isPartial ? 'var(--accent-yellow)' : 'var(--accent-red)')}
            />
          </div>
        </SectionCard>
      </section>

      <EnvVarsSection envVars={service.environmentVariables || {}} />

      <ContainersPanel
        projectId={projectId}
        serviceName={service.serviceName}
        reloadKey={reloadKey}
        onLogs={setLogsTarget}
        onStop={async (id) => {
          try {
            await containerService.stop(id);
            success('Container stopped.');
            refreshAll();
          } catch (e) {
            error(extractError(e));
          }
        }}
        onRemove={(id) => setConfirmCfg({
          title: 'Delete Container',
          message: 'This container will be stopped and removed. The target replica count of the service has not changed, so self-healing may create a new one.',
          confirmLabel: 'Delete',
          action: async () => {
            try {
              await containerService.remove(id);
              success('Container deleted.');
              refreshAll();
            } catch (e) {
              error(extractError(e));
            }
            setConfirmCfg(null);
          },
        })}
      />

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

const StatusBanner = ({ isHealthy, isPartial, isStopped, inCooldown, running, desired, consecutiveFailures, lastFailureAt }) => {
  let color;
  let icon;
  let text;
  let sub;

  if (inCooldown) {
    color = 'var(--accent-red)';
    icon = ShieldAlert;
    text = `Self-healing disabled - ${consecutiveFailures} failed deploy attempts`;
    sub = `Last error: ${formatRelative(lastFailureAt)}. 5-minute cooldown active.`;
  } else if (isHealthy) {
    color = 'var(--accent-green)';
    icon = Activity;
    text = 'Service healthy';
    sub = `${running}/${desired} replicas running.`;
  } else if (isPartial) {
    color = 'var(--accent-yellow)';
    icon = AlertTriangle;
    text = 'Partially running';
    sub = `${running}/${desired} replicas up. Self-healing is trying to restore capacity.`;
  } else if (isStopped) {
    color = 'var(--text-muted)';
    icon = Square;
    text = 'Service stopped';
    sub = 'Replica count is 0. Scale up to start.';
  } else {
    color = 'var(--accent-red)';
    icon = AlertTriangle;
    text = 'Service not running';
    sub = `${running}/${desired} replicas up.`;
  }

  return (
    <div
      className="status-banner"
      style={{
        '--status-color': color,
        '--status-border': `color-mix(in srgb, ${color} 42%, transparent)`,
        '--status-bg': `color-mix(in srgb, ${color} 10%, var(--bg-card))`,
      }}
    >
      <div className="icon-box" style={{ color }}>
        {React.createElement(icon, { size: 18 })}
      </div>
      <div>
        <div className="status-banner-title">{text}</div>
        <div className="status-banner-sub">{sub}</div>
      </div>
    </div>
  );
};

const StatCard = ({ icon: Icon, label, value, hint, tone, mono, spark }) => {
  const color = tone === 'green'
    ? 'var(--accent-green)'
    : tone === 'yellow'
      ? 'var(--accent-yellow)'
      : tone === 'red'
        ? 'var(--accent-red)'
        : 'var(--text-muted)';

  return (
    <div className="stat-box">
      <div className="stat-box-top">
        <div className="stat-box-label">{label}</div>
        <div className="icon-box" style={{ color }}>
          {React.createElement(Icon, { size: 16 })}
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 10 }}>
        <div className={`stat-box-value ${mono ? 'mono' : ''}`}>{value}</div>
        {spark && <div style={{ flexShrink: 0 }}>{spark}</div>}
      </div>
      {hint && <div className="stat-box-hint" style={{ color: tone ? color : undefined }}>{hint}</div>}
    </div>
  );
};

const SectionCard = ({ title, subtitle, icon: Icon, children }) => (
  <div className="section-card">
    <div className="section-card-header">
      <div>
        <div className="section-card-title">
          {Icon && React.createElement(Icon, { size: 16 })}
          {title}
        </div>
        {subtitle && <div className="panel-subtitle">{subtitle}</div>}
      </div>
    </div>
    <div className="section-card-body">
      {children}
    </div>
  </div>
);

const EndpointRow = ({ label, badge, description, lines, disabled = false }) => (
  <div className="endpoint-box">
    <div className="endpoint-head">
      <div className="kv-label">{label}</div>
      <Badge variant={disabled ? 'gray' : 'blue'}>{badge}</Badge>
      <div className="endpoint-hint">{description}</div>
    </div>
    <div className="endpoint-stack">
      {lines.map(line => (
        <CopyLine key={line.label} label={line.label} value={line.value} disabled={disabled} />
      ))}
    </div>
  </div>
);

const CopyLine = ({ label, value, disabled }) => {
  const [copied, setCopied] = useState(false);
  const canCopy = !disabled && value && !value.toLowerCase().includes('disabled');

  const handleCopy = () => {
    if (!canCopy) return;
    navigator.clipboard.writeText(value);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div>
      <div className="kv-label" style={{ marginBottom: 4 }}>{label}</div>
      <div className="endpoint-url">
        <code className="mono" style={{ color: disabled ? 'var(--text-muted)' : undefined }}>{value}</code>
        {canCopy && (
          <button className="btn-icon" onClick={handleCopy} title="Copy">
            {copied ? <Check size={14} color="var(--accent-green)" /> : <Copy size={14} />}
          </button>
        )}
      </div>
    </div>
  );
};

const KvRow = ({ icon: Icon, label, value, valueColor, mono }) => (
  <div className="kv-row">
    <div className="kv-row-label">
      {Icon && React.createElement(Icon, { size: 14 })}
      {label}
    </div>
    <div className={`kv-row-value ${mono ? 'mono' : ''}`} style={{ color: valueColor }}>
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
    <div className="section-card" style={{ marginTop: 18, marginBottom: 18 }}>
      <div className="section-card-header">
        <div>
          <div className="section-card-title">
            <Zap size={16} />
            Environment variables
            <Badge variant="gray">{entries.length}</Badge>
          </div>
          <div className="panel-subtitle">Service-level runtime configuration</div>
        </div>
        {entries.length > 0 && (
          <Button size="sm" variant="ghost" icon={showValues ? EyeOff : Eye} onClick={() => setShowValues(!showValues)}>
            {showValues ? 'Hide' : 'Show'}
          </Button>
        )}
      </div>

      {entries.length === 0 ? (
        <div className="empty-state" style={{ padding: 34 }}>
          <p>No environment variables defined for this service.</p>
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
                  <td className="mono" style={{ color: 'var(--accent-cyan)', fontSize: '.8rem' }}>{key}</td>
                  <td className="mono" style={{ fontSize: '.8rem', wordBreak: 'break-all' }}>
                    {showValues ? value : '*'.repeat(Math.min(value?.length || 8, 24))}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn-icon" onClick={() => handleCopy(key, value)} title="Copy value">
                      {copiedKey === key ? <Check size={14} color="var(--accent-green)" /> : <Copy size={14} />}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

const ScaleModal = ({ image, onClose, onSuccess }) => {
  const { error, success } = useToast();
  const [replicas, setReplicas] = useState(image.desiredReplicas);
  const [loading, setLoading] = useState(false);

  const handleScale = async () => {
    setLoading(true);
    try {
      await projectService.scale(image.id, replicas);
      success('Scaling successful.');
      onSuccess();
      onClose();
    } catch (err) {
      error(extractError(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal isOpen onClose={onClose} title={`Scale Service: ${image.serviceName}`}>
      <p style={{ fontSize: '.85rem', color: 'var(--text-muted)', marginBottom: 18 }}>
        Current: <strong>{image.runningReplicas}/{image.desiredReplicas}</strong> replicas. Set the new target count.
      </p>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24 }}>
        <input
          type="range"
          min="0"
          max="10"
          value={replicas}
          onChange={(e) => setReplicas(parseInt(e.target.value, 10))}
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

function formatDate(iso) {
  if (!iso) return '-';
  try {
    return new Date(iso).toLocaleString('en-US', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

function formatRelative(iso) {
  if (!iso) return '-';
  const date = new Date(iso);
  const diffSec = (Date.now() - date.getTime()) / 1000;
  if (diffSec < 60) return `${Math.floor(diffSec)}s ago`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}
