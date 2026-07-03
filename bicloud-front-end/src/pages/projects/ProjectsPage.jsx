import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { projectService } from '../../services/project.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../context/AuthContext';
import { Badge, Button, Spinner, Input } from '../../components/ui';
import { ConfirmModal } from '../../components/ui/ConfirmModal';
import { CreateProjectModal } from './modals/CreateProjectModal';
import {
  ArrowRight,
  Box,
  FolderKanban,
  Layers,
  Plus,
  Search,
  Server,
  ShieldCheck,
  Square,
  Play,
  Trash2,
} from 'lucide-react';

export default function ProjectsPage() {
  const navigate = useNavigate();
  const { isAdmin, user } = useAuth();
  const { error, success } = useToast();

  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [deleteTarget, setDeleteTarget] = useState(null);

  const fetchProjects = useCallback(async () => {
    try {
      const { data } = await projectService.list();
      setProjects(data || []);
    } catch (err) {
      error(extractError(err));
    } finally {
      setLoading(false);
    }
  }, [error]);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) fetchProjects();
    });
    return () => { cancelled = true; };
  }, [fetchProjects]);

  const filtered = useMemo(() => {
    const q = searchTerm.trim().toLowerCase();
    if (!q) return projects;
    return projects.filter(p =>
      p.name.toLowerCase().includes(q) ||
      (p.ownerUsername && p.ownerUsername.toLowerCase().includes(q))
    );
  }, [projects, searchTerm]);

  const summary = useMemo(() => {
    const services = filtered.flatMap(p => p.images || []);
    return {
      projects: filtered.length,
      services: services.length,
      running: filtered.reduce((s, p) => s + (p.totalRunningContainers || 0), 0),
      exposed: services.filter(s => s.exposeExternally).length,
    };
  }, [filtered]);

  const mine = filtered.filter(p => p.ownerUsername === user?.username);
  const otherProjects = isAdmin ? filtered.filter(p => p.ownerUsername !== user?.username) : [];

  const handleDeploy = async (e, id) => {
    e.preventDefault();
    e.stopPropagation();
    try {
      await projectService.deploy(id);
      success('Deploy started.');
      fetchProjects();
    } catch (err) {
      error(extractError(err));
    }
  };

  const handleUndeploy = async (e, id) => {
    e.preventDefault();
    e.stopPropagation();
    if (!window.confirm('All services in this project will be stopped. Are you sure?')) return;
    try {
      await projectService.undeploy(id);
      success('Project undeployed.');
      fetchProjects();
    } catch (err) {
      error(extractError(err));
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await projectService.delete(deleteTarget.id);
      success(`Project "${deleteTarget.name}" deleted.`);
      setDeleteTarget(null);
      fetchProjects();
    } catch (err) {
      error(extractError(err));
      setDeleteTarget(null);
    }
  };

  if (loading) return <div className="page-loader"><Spinner size="lg" /></div>;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <div className="page-kicker"><FolderKanban size={14} /> Projects</div>
          <h1 className="page-title">Service projects</h1>
          <p className="page-subtitle">
            Manage tenant boundaries, service definitions, deployment state, and gateway exposure.
          </p>
        </div>
        <div className="page-actions">
          <Button icon={Plus} onClick={() => setIsModalOpen(true)}>New Project</Button>
        </div>
      </header>

      <section className="console-grid metrics" style={{ marginBottom: 18 }}>
        <MetricCard label="Projects" value={summary.projects} icon={FolderKanban} />
        <MetricCard label="Services" value={summary.services} icon={Layers} />
        <MetricCard label="Running Containers" value={summary.running} icon={Box} />
        <MetricCard label="External Routes" value={summary.exposed} icon={ShieldCheck} />
      </section>

      <div className="toolbar">
        <div className="search">
          <Input
            placeholder="Search projects or owners..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            icon={Search}
          />
        </div>
        <div className="status-strip">
          <Server size={14} />
          {summary.running} running containers across {summary.projects} visible projects
        </div>
      </div>

      <ProjectSection
        title="My Projects"
        description="Projects owned by your account"
        projects={mine}
        isAdmin={isAdmin}
        emptyText="No projects owned by your account match this view."
        onCreate={() => setIsModalOpen(true)}
        onDeploy={handleDeploy}
        onUndeploy={handleUndeploy}
        onDelete={(project) => setDeleteTarget(project)}
      />

      {isAdmin && (
        <ProjectSection
          title="Other Users' Projects"
          description="Admin-visible projects owned by other users"
          projects={otherProjects}
          isAdmin={isAdmin}
          emptyText="No projects from other users match this view."
          onDeploy={handleDeploy}
          onUndeploy={handleUndeploy}
          onDelete={(project) => setDeleteTarget(project)}
        />
      )}

      <CreateProjectModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSuccess={(p) => navigate(`/projects/${p.id}`)}
      />

      <ConfirmModal
        isOpen={!!deleteTarget}
        title="Delete Project"
        message={deleteTarget && <>
          <strong>{deleteTarget.name}</strong> project will be permanently deleted along with all its services and
          containers. This action cannot be undone.
        </>}
        confirmLabel="Delete Project"
        requireText={deleteTarget?.name}
        onConfirm={handleDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </div>
  );
}

const ProjectSection = ({
  title,
  description,
  projects,
  isAdmin,
  emptyText,
  onCreate,
  onDeploy,
  onUndeploy,
  onDelete,
}) => (
  <section className="panel" style={{ marginBottom: 22 }}>
    <div className="panel-header">
      <div>
        <div className="panel-title"><FolderKanban size={16} /> {title}</div>
        <div className="panel-subtitle">{description}</div>
      </div>
      <Badge variant="gray">{projects.length}</Badge>
    </div>

    {projects.length === 0 ? (
      <div className="empty-state" style={{ padding: '42px 20px' }}>
        <FolderKanban size={34} />
        <p>{emptyText}</p>
        {onCreate && <Button variant="ghost" icon={Plus} onClick={onCreate}>Create Project</Button>}
      </div>
    ) : (
      <div className="project-table">
        <div className="project-head">
          <div>Project</div>
          <div>Services</div>
          <div>Running</div>
          <div>External</div>
          <div>Actions</div>
        </div>
        {projects.map(project => (
          <ProjectRow
            key={project.id}
            project={project}
            isAdmin={isAdmin}
            onDeploy={onDeploy}
            onUndeploy={onUndeploy}
            onDelete={onDelete}
          />
        ))}
      </div>
    )}
  </section>
);

const ProjectRow = ({ project, isAdmin, onDeploy, onUndeploy, onDelete }) => {
  const navigate = useNavigate();
  const running = project.totalRunningContainers || 0;
  const services = project.images?.length || 0;
  const exposed = (project.images || []).filter(img => img.exposeExternally).length;
  const openProject = () => navigate(`/projects/${project.id}`);
  const handleKeyDown = (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      openProject();
    }
  };

  return (
    <div
      className="project-row"
      role="link"
      tabIndex={0}
      onClick={openProject}
      onKeyDown={handleKeyDown}
    >
      <div className="resource-primary">
        <div className={`health-dot ${running > 0 ? 'running' : 'stopped'}`} />
        <div style={{ minWidth: 0 }}>
          <div className="resource-title">{project.name}</div>
          <div className="resource-subtitle">
            {isAdmin ? `Owner: ${project.ownerUsername}` : `Project ID: ${project.id}`}
          </div>
        </div>
      </div>

      <SmallMetric className="project-metric" label="Services" value={services} />
      <SmallMetric className="project-metric" label="Running" value={running} />
      <SmallMetric className="project-metric" label="External" value={exposed} />

      <div className="project-actions">
        <Badge variant={running > 0 ? 'green' : 'gray'}>{running > 0 ? 'Running' : 'Stopped'}</Badge>
        <Button size="sm" variant="success" onClick={(e) => onDeploy(e, project.id)} icon={Play}>Deploy</Button>
        {running > 0 ? (
          <Button size="sm" variant="danger" onClick={(e) => onUndeploy(e, project.id)} icon={Square}>Stop</Button>
        ) : (
          <span className="action-placeholder" aria-hidden="true" />
        )}
        <Button
          size="sm"
          variant="ghost"
          icon={Trash2}
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            onDelete(project);
          }}
          style={{ color: 'var(--accent-red)' }}
          title="Delete project"
        />
        <ArrowRight size={15} color="var(--text-muted)" />
      </div>
    </div>
  );
};

const SmallMetric = ({ label, value, className = '' }) => (
  <div className={`kv-metric ${className}`}>
    <div className="kv-label">{label}</div>
    <div className="kv-value">{value}</div>
  </div>
);

const MetricCard = ({ label, value, icon: Icon }) => (
  <div className="metric-tile" style={{ minHeight: 96 }}>
    <div className="metric-topline">
      <div className="metric-name">{label}</div>
      <div className="icon-box">{React.createElement(Icon, { size: 16 })}</div>
    </div>
    <div className="metric-number">{value}</div>
  </div>
);
