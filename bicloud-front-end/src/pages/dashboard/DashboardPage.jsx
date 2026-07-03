import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { projectService } from '../../services/project.service';
import { extractError } from '../../utils/common';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { Badge, Spinner, Button } from '../../components/ui';
import {
  ArrowRight,
  Box,
  Cloud,
  FolderKanban,
  Globe,
  Layers,
  Plus,
  ShieldCheck,
} from 'lucide-react';

export default function DashboardPage() {
  const { user } = useAuth();
  const { error } = useToast();
  const navigate = useNavigate();
  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      projectService.list()
        .then(r => {
          if (!cancelled) setProjects(r.data || []);
        })
        .catch(err => {
          if (!cancelled) error(extractError(err));
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    });
    return () => { cancelled = true; };
  }, [error]);

  const summary = useMemo(() => {
    const services = projects.flatMap(p => p.images || []);
    const runningContainers = projects.reduce((s, p) => s + (p.totalRunningContainers || 0), 0);
    const exposedServices = services.filter(s => s.exposeExternally).length;
    const activeProjects = projects.filter(p => p.totalRunningContainers > 0).length;

    return {
      totalProjects: projects.length,
      activeProjects,
      services: services.length,
      runningContainers,
      exposedServices,
      internalOnlyServices: services.length - exposedServices,
    };
  }, [projects]);

  const recentProjects = [...projects]
    .sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
    .slice(0, 12);

  if (loading) return <div className="page-loader"><Spinner size="lg" /></div>;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <div className="page-kicker"><Cloud size={14} /> Workspace Overview</div>
          <h1 className="page-title">Cloud resources</h1>
          <p className="page-subtitle">
            Operational view for {user?.username}: projects, services, running containers, and gateway exposure.
          </p>
        </div>
        <div className="page-actions">
          <Button variant="ghost" icon={FolderKanban} onClick={() => navigate('/projects')}>
            View Projects
          </Button>
          <Button icon={Plus} onClick={() => navigate('/projects')}>New Project</Button>
        </div>
      </header>

      <section className="summary-strip">
        <SummaryItem
          icon={FolderKanban}
          label="Projects"
          value={summary.totalProjects}
          detail={`${summary.activeProjects} active`}
          accent="var(--accent-blue)"
        />
        <SummaryItem
          icon={Layers}
          label="Services"
          value={summary.services}
          detail={`${summary.internalOnlyServices} mesh-only`}
          accent="var(--accent-cyan)"
        />
        <SummaryItem
          icon={Box}
          label="Containers"
          value={summary.runningContainers}
          detail="running instances"
          accent="var(--accent-green)"
        />
        <SummaryItem
          icon={ShieldCheck}
          label="External routes"
          value={summary.exposedServices}
          detail="gateway exposed"
          accent="var(--accent-yellow)"
        />
      </section>

      <section className="section-card">
        <div className="section-card-header">
          <div>
            <div className="section-card-title"><FolderKanban size={16} /> Resource inventory</div>
            <div className="panel-subtitle">Project definitions with runtime and routing state</div>
          </div>
        </div>

        {recentProjects.length === 0 ? (
          <EmptyWorkspace onCreate={() => navigate('/projects')} />
        ) : (
          <div className="table-wrap">
            <table className="resource-table">
              <thead>
                <tr>
                  <th>Project</th>
                  <th>Owner</th>
                  <th style={{ textAlign: 'right' }}>Services</th>
                  <th style={{ textAlign: 'right' }}>Running</th>
                  <th style={{ textAlign: 'right' }}>External</th>
                  <th>Status</th>
                  <th style={{ width: 48 }}></th>
                </tr>
              </thead>
              <tbody>
                {recentProjects.map(project => (
                  <ProjectRow
                    key={project.id}
                    project={project}
                    onOpen={() => navigate(`/projects/${project.id}`)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

const SummaryItem = ({ icon: Icon, label, value, detail, accent }) => (
  <div className="summary-item">
    <div className="icon-box" style={{ color: accent, borderColor: `${accent}44`, background: `${accent}12` }}>
      {React.createElement(Icon, { size: 17 })}
    </div>
    <div>
      <div className="summary-value">{value}</div>
      <div className="summary-label">{label}</div>
      <div className="summary-detail">{detail}</div>
    </div>
  </div>
);

const ProjectRow = ({ project, onOpen }) => {
  const running = project.totalRunningContainers || 0;
  const services = project.images?.length || 0;
  const exposed = (project.images || []).filter(img => img.exposeExternally).length;

  const handleKeyDown = (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onOpen();
    }
  };

  return (
    <tr onClick={onOpen} onKeyDown={handleKeyDown} tabIndex={0}>
      <td>
        <div className="resource-primary">
          <div className={`health-dot ${running > 0 ? 'running' : 'stopped'}`} />
          <div style={{ minWidth: 0 }}>
            <div className="resource-title">{project.name}</div>
            <div className="resource-subtitle">Project ID: {project.id}</div>
          </div>
        </div>
      </td>
      <td className="muted-cell">{project.ownerUsername}</td>
      <td className="number-cell" style={{ textAlign: 'right' }}>{services}</td>
      <td className="number-cell" style={{ textAlign: 'right' }}>{running}</td>
      <td className="number-cell" style={{ textAlign: 'right' }}>{exposed}</td>
      <td>
        <Badge variant={running > 0 ? 'green' : 'gray'}>{running > 0 ? 'Running' : 'Stopped'}</Badge>
      </td>
      <td style={{ textAlign: 'right' }}>
        <ArrowRight size={15} color="var(--text-muted)" />
      </td>
    </tr>
  );
};

const EmptyWorkspace = ({ onCreate }) => (
  <div className="empty-state">
    <FolderKanban size={38} />
    <div>
      <div style={{ color: 'var(--text-primary)', fontWeight: 700, marginBottom: 4 }}>No projects yet</div>
      <p>Create a project, add services, and deploy containers to workers.</p>
    </div>
    <Button variant="ghost" icon={Plus} onClick={onCreate}>Create Project</Button>
  </div>
);
