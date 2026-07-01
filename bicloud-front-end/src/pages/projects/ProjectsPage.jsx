import React, { useState, useEffect, useCallback } from 'react';
import { projectService } from '../../services/project.service';
import { extractError } from '../../utils/common';
import { useToast } from '../../context/ToastContext';
import { useAuth } from '../../context/AuthContext';
import { Card, Button, Badge, Spinner, Input } from '../../components/ui';
import { ConfirmModal } from '../../components/ui/ConfirmModal';
import { CreateProjectModal } from './modals/CreateProjectModal';
import { useNavigate } from 'react-router-dom';
import { Plus, Search, Play, Square, ExternalLink, Package, Trash2 } from 'lucide-react';

export default function ProjectsPage() {
  const navigate = useNavigate();
  const { isAdmin, user } = useAuth();
  const { error, success } = useToast();

  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [deleteTarget, setDeleteTarget] = useState(null); // project to delete

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
    fetchProjects();
  }, [fetchProjects]);

  const handleDeploy = async (e, id) => {
    e.stopPropagation();
    try {
      await projectService.deploy(id);
      success('Deploy started.');
      fetchProjects();
    } catch (err) { error(extractError(err)); }
  };

  const handleUndeploy = async (e, id) => {
    e.stopPropagation();
    if (!window.confirm('All services in this project will be stopped. Are you sure?')) return;
    try {
      await projectService.undeploy(id);
      success('Project undeployed.');
      fetchProjects();
    } catch (err) { error(extractError(err)); }
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

  const filtered = projects.filter(p =>
    p.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    (p.ownerUsername && p.ownerUsername.toLowerCase().includes(searchTerm.toLowerCase()))
  );

  const myProjects = filtered.filter(p => p.ownerUsername === user?.username);
  const otherProjects = isAdmin ? filtered.filter(p => p.ownerUsername !== user?.username) : [];

  const renderGrid = (list) => (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: 20 }}>
      {list.map(project => (
        <ProjectCard
          key={project.id}
          project={project}
          isAdmin={isAdmin}
          onDeploy={(e) => handleDeploy(e, project.id)}
          onUndeploy={(e) => handleUndeploy(e, project.id)}
          onDelete={(e) => { e.stopPropagation(); setDeleteTarget(project); }}
          onClick={() => navigate(`/projects/${project.id}`)}
        />
      ))}
    </div>
  );

  return (
    <div style={{ padding: '32px' }}>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 32 }}>
        <div>
          <h1 style={{ fontSize: '1.75rem', fontWeight: 800, marginBottom: 4 }}>Projects</h1>
          <p style={{ color: 'var(--text-muted)' }}>Manage your current container projects.</p>
        </div>
        <Button onClick={() => setIsModalOpen(true)} icon={Plus}>New Project</Button>
      </header>

      <div style={{ marginBottom: 24, maxWidth: 400 }}>
        <Input
          placeholder="Search projects or users..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          icon={Search}
        />
      </div>

      {loading ? (
        <div className="page-loader"><Spinner size="lg" /></div>
      ) : (
        <>
          <section style={{ marginBottom: 40 }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 16 }}>
              My Projects
            </h2>
            {myProjects.length === 0 ? (
              <Card style={{ textAlign: 'center', padding: '48px 20px' }}>
                <div style={{ color: 'var(--text-muted)', marginBottom: 20 }}>
                  <Package size={40} style={{ opacity: 0.2, marginBottom: 12 }} />
                  <p>You don't have any projects yet.</p>
                </div>
                <Button onClick={() => setIsModalOpen(true)} variant="ghost">Create Your First Project</Button>
              </Card>
            ) : renderGrid(myProjects)}
          </section>

          {isAdmin && (
            <section>
              <h2 style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 16 }}>
                Other Users' Projects
              </h2>
              {otherProjects.length === 0 ? (
                <Card style={{ textAlign: 'center', padding: '48px 20px', color: 'var(--text-muted)' }}>
                  <Package size={40} style={{ opacity: 0.2, marginBottom: 12 }} />
                  <p>No projects found from other users.</p>
                </Card>
              ) : renderGrid(otherProjects)}
            </section>
          )}
        </>
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

const ProjectCard = ({ project, isAdmin, onDeploy, onUndeploy, onDelete, onClick }) => (
  <Card style={{ cursor: 'pointer', transition: 'transform 0.2s' }} onClick={onClick} className="list-item-hover">
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div className={`health-dot ${project.totalRunningContainers > 0 ? 'running' : 'stopped'}`} />
        <div>
          <h3 style={{ fontSize: '1.1rem', fontWeight: 700 }}>{project.name}</h3>
          {isAdmin && <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>Owner: {project.ownerUsername}</span>}
        </div>
      </div>
      <Badge variant={project.totalRunningContainers > 0 ? 'green' : 'gray'}>
        {project.totalRunningContainers > 0 ? 'Active' : 'Idle'}
      </Badge>
    </div>

    <div style={{ display: 'flex', gap: 16, marginBottom: 20, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
      <span>📦 {project.images?.length || 0} Services</span>
      <span>⬡ {project.totalRunningContainers || 0} Container</span>
    </div>

    <div style={{ display: 'flex', gap: 10 }} onClick={e => e.stopPropagation()}>
      <Button size="sm" variant="success" onClick={onDeploy} icon={Play}>Deploy</Button>
      {project.totalRunningContainers > 0 && (
        <Button size="sm" variant="danger" onClick={onUndeploy} icon={Square}>Stop</Button>
      )}
      <Button size="sm" variant="ghost" icon={Trash2} onClick={onDelete}
              style={{ marginLeft: 'auto', color: 'var(--accent-red)' }} title="Delete project" />
      <Button size="sm" variant="ghost" icon={ExternalLink} />
    </div>
  </Card>
);
