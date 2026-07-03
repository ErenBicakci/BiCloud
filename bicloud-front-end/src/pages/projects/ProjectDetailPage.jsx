import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { projectService } from '../../services/project.service';
import { containerService } from '../../services/container.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { Card, Badge, Button, Spinner } from '../../components/ui';
import { Modal } from '../../components/ui/Modal';
import { ConfirmModal } from '../../components/ui/ConfirmModal';
import { ContainersPanel } from '../../components/ui/ContainersPanel';
import { LogsModal } from '../../components/ui/LogsModal';
import { AuditTimeline } from '../../components/ui/AuditTimeline';
import { auditService } from '../../services/audit.service';
import { AddImageModal } from './modals/AddImageModal';
import { EditServiceModal } from './modals/EditServiceModal';
import {
  ChevronLeft,
  Play,
  Square,
  RefreshCw,
  Settings,
  Terminal,
  Trash2,
  Plus,
  Box,
  Layers,
  ChevronRight,
  AlertTriangle,
  PenLine,
  History,
  Network,
} from 'lucide-react';

export default function ProjectDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { error, success } = useToast();

  const [project, setProject] = useState(null);
  const [loading, setLoading] = useState(true);
  const [deploying, setDeploying] = useState(false);
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('services');
  const [reloadKey, setReloadKey] = useState(0);
  const [logsTarget, setLogsTarget] = useState(null);
  const [scaleTarget, setScaleTarget] = useState(null);
  const [editTarget, setEditTarget] = useState(null);
  const [confirmCfg, setConfirmCfg] = useState(null);

  const loadData = useCallback(async () => {
    try {
      const projRes = await projectService.get(id);
      setProject(projRes.data);
    } catch (err) {
      error(extractError(err));
      navigate('/projects');
    } finally {
      setLoading(false);
    }
  }, [id, error, navigate]);

  const refreshAll = useCallback(() => {
    loadData();
    setReloadKey(k => k + 1);
  }, [loadData]);

  const projectAuditFetcher = useCallback(
    (params) => auditService.projectFeed(id, params),
    [id]
  );

  useEffect(() => { loadData(); }, [loadData]);

  const handleDeploy = async () => {
    setDeploying(true);
    try {
      await projectService.deploy(id);
      success('Deploy started.');
      setTimeout(refreshAll, 2000);
    } catch (err) { error(extractError(err)); } finally { setDeploying(false); }
  };

  const handleUndeploy = () => setConfirmCfg({
    title: 'Stop Project',
    message: <>All <strong>service containers</strong> in the project will be stopped. Service definitions are not deleted; you can redeploy later.</>,
    confirmLabel: 'Stop',
    action: async () => {
      try {
        await projectService.undeploy(id);
        success('Project stopped.');
        setTimeout(refreshAll, 1000);
      } catch (err) { error(extractError(err)); }
      setConfirmCfg(null);
    },
  });

  const handleStopInstance = async (instanceId) => {
    try {
      await containerService.stop(instanceId);
      success('Container stopped.');
      refreshAll();
    } catch (err) { error(extractError(err)); }
  };

  const handleRemoveInstance = (instanceId) => setConfirmCfg({
    title: 'Delete Container',
    message: 'This container will be stopped and removed. The target replica count of the service has not changed, so self-healing may create a new one.',
    confirmLabel: 'Delete',
    action: async () => {
      try {
        await containerService.remove(instanceId);
        success('Container deleted.');
        refreshAll();
      } catch (err) { error(extractError(err)); }
      setConfirmCfg(null);
    },
  });

  const handleDeleteService = (img) => setConfirmCfg({
    title: 'Delete Service',
    message: <>
      <strong>{img.serviceName}</strong> service will be permanently deleted.
      All running containers will be stopped and removed, and environment variable
      definitions will also be deleted. This action cannot be undone.
    </>,
    confirmLabel: 'Delete Service',
    action: async () => {
      try {
        await projectService.deleteImage(img.id);
        success(`Service "${img.serviceName}" deleted.`);
        loadData();
      } catch (err) { error(extractError(err)); }
      setConfirmCfg(null);
    },
  });

  const handleDeleteProject = () => setConfirmCfg({
    title: 'Delete Project',
    message: <>
      <strong>{project?.name}</strong> project will be permanently deleted along with all its services and containers.
      This action cannot be undone.
    </>,
    confirmLabel: 'Delete Project',
    requireText: project?.name,
    action: async () => {
      try {
        await projectService.delete(id);
        success('Project deleted.');
        navigate('/projects');
      } catch (err) {
        error(extractError(err));
        setConfirmCfg(null);
      }
    },
  });

  if (loading) return <div className="page-loader"><Spinner size="lg" /></div>;
  if (!project) return null;

  return (
    <div style={{ padding: '32px' }}>
      {/* Header */}
      <div style={{ marginBottom: 32 }}>
        <Link to="/projects" style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--text-muted)', textDecoration: 'none', fontSize: '0.85rem', marginBottom: 16 }}>
          <ChevronLeft size={16} /> Back to Projects
        </Link>
        
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
              <h1 style={{ fontSize: '2rem', fontWeight: 800 }}>{project.name}</h1>
              <Badge variant={project.totalRunningContainers > 0 ? 'green' : 'gray'}>
                {project.totalRunningContainers > 0 ? 'Running' : 'Stopped'}
              </Badge>
            </div>
            <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
              Project ID: <span className="mono">{project.id}</span> • Owner: <strong>{project.ownerUsername}</strong>
            </p>
          </div>

          <div style={{ display: 'flex', gap: 10 }}>
            <Button variant="ghost" icon={Plus} onClick={() => setIsAddModalOpen(true)}>Add Service</Button>
            <Button variant="success" icon={Play} onClick={handleDeploy} loading={deploying}>Deploy</Button>
            {project.totalRunningContainers > 0 && (
              <Button variant="danger" icon={Square} onClick={handleUndeploy}>Undeploy</Button>
            )}
            <Button variant="ghost" icon={RefreshCw} onClick={loadData} />
            <Button variant="ghost" icon={Trash2} onClick={handleDeleteProject}
                    style={{ color: 'var(--accent-red)' }} title="Delete project" />
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)', marginBottom: 24 }}>
        <Tab active={activeTab === 'services'} onClick={() => setActiveTab('services')} icon={Layers}>Services</Tab>
        <Tab active={activeTab === 'containers'} onClick={() => setActiveTab('containers')} icon={Box}>Containers</Tab>
        <Tab active={activeTab === 'activity'} onClick={() => setActiveTab('activity')} icon={History}>Activity</Tab>
      </div>

      {activeTab === 'services' && (
        <ServicesTab
          projectId={id}
          images={project.images || []}
          onScale={(img) => setScaleTarget(img)}
          onEdit={(img) => setEditTarget(img)}
          onDelete={handleDeleteService}
        />
      )}
      {activeTab === 'containers' && (
        <ContainersPanel
          projectId={id}
          reloadKey={reloadKey}
          onLogs={setLogsTarget}
          onStop={handleStopInstance}
          onRemove={handleRemoveInstance}
        />
      )}
      {activeTab === 'activity' && (
        <Card>
          <AuditTimeline
            fetcher={projectAuditFetcher}
            compact
            emptyText="No activity recorded for this project yet."
          />
        </Card>
      )}

      {/* Modals */}
      <AddImageModal 
        projectId={id} 
        isOpen={isAddModalOpen} 
        onClose={() => setIsAddModalOpen(false)} 
        onSuccess={loadData}
      />

      <LogsModal 
        instance={logsTarget} 
        onClose={() => setLogsTarget(null)} 
      />

      {scaleTarget && (
        <ScaleModal
          image={scaleTarget}
          onClose={() => setScaleTarget(null)}
          onSuccess={loadData}
        />
      )}

      <EditServiceModal
        service={editTarget}
        isOpen={!!editTarget}
        onClose={() => setEditTarget(null)}
        onSuccess={loadData}
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

const Tab = ({ active, onClick, children, icon: Icon }) => (
  <button onClick={onClick} style={{
    padding: '12px 24px', background: 'none', border: 'none', cursor: 'pointer',
    display: 'flex', alignItems: 'center', gap: 8, fontWeight: 600, fontSize: '0.9rem',
    color: active ? 'var(--accent-blue)' : 'var(--text-muted)',
    borderBottom: `2px solid ${active ? 'var(--accent-blue)' : 'transparent'}`,
    transition: 'all 0.2s', marginBottom: -1
  }}>
    <Icon size={18} /> {children}
  </button>
);

const ServicesTab = ({ projectId, images, onScale, onEdit, onDelete }) => {
  const navigate = useNavigate();

  if (images.length === 0) return <div style={{ textAlign: 'center', padding: '40px', color: 'var(--text-muted)' }}>No services defined yet.</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {images.map(img => {
        const inCooldown = img.consecutiveDeployFailures >= 5 && img.lastDeployFailureAt;
        const isHealthy  = img.runningReplicas === img.desiredReplicas && img.desiredReplicas > 0;

        return (
          <Card
            key={img.id}
            className="list-item-hover"
            style={{
              display: 'flex', alignItems: 'center', gap: 16, cursor: 'pointer',
              borderLeft: `3px solid ${inCooldown ? 'var(--accent-red)' : (isHealthy ? 'var(--accent-green)' : 'var(--accent-yellow)')}`
            }}
            onClick={() => navigate(`/projects/${projectId}/services/${img.id}`)}
          >
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
                <span style={{ fontWeight: 700 }}>{img.serviceName}</span>
                <Badge variant={img.exposeExternally ? 'blue' : 'gray'}>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                    <Network size={11} /> {img.exposeExternally ? 'External' : 'Mesh only'}
                  </span>
                </Badge>
                {inCooldown && (
                  <Badge variant="red">
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                      <AlertTriangle size={11} /> Cooldown
                    </span>
                  </Badge>
                )}
              </div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }} className="mono truncate">{img.imageName}</div>
            </div>

            <div style={{ display: 'flex', gap: 28 }}>
              <Stat label="Replica" value={`${img.runningReplicas} / ${img.desiredReplicas}`} />
              <Stat label="Port" value={img.containerPort} />
              <Stat label="Memory" value={`${img.memoryLimitMb}MB`} />
              <Stat label="CPU" value={img.cpuLimit} />
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }} onClick={e => e.stopPropagation()}>
              <Button size="sm" variant="ghost" onClick={() => onScale(img)} icon={Settings}>Scale</Button>
              <Button size="sm" variant="ghost" onClick={() => onEdit(img)} icon={PenLine} title="Edit service" />
              <Button size="sm" variant="ghost" onClick={() => onDelete(img)} icon={Trash2}
                      style={{ color: 'var(--accent-red)' }} title="Delete service" />
              <ChevronRight size={18} color="var(--text-muted)" />
            </div>
          </Card>
        );
      })}
    </div>
  );
};


const Stat = ({ label, value }) => (
  <div>
    <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: 2 }}>{label}</div>
    <div style={{ fontSize: '0.9rem', fontWeight: 600 }}>{value}</div>
  </div>
);


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
    } catch (err) { error(extractError(err)); } finally { setLoading(false); }
  };

  return (
    <Modal isOpen={!!image} onClose={onClose} title={`Scale Service: ${image.serviceName}`}>
      <div style={{ marginBottom: 24 }}>
        <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', marginBottom: 16 }}>
          Set the number of replicas to run for this service.
        </p>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <input 
            type="range" min="0" max="10" value={replicas} 
            onChange={(e) => setReplicas(parseInt(e.target.value))}
            style={{ flex: 1 }}
          />
          <div style={{ fontSize: '1.5rem', fontWeight: 800, width: 40, textAlign: 'center' }}>{replicas}</div>
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={handleScale} loading={loading}>Update</Button>
      </div>
    </Modal>
  );
};
