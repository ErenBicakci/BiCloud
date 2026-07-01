import React, { useState, useEffect } from 'react';
import { projectService } from '../../services/project.service';
import { extractError } from '../../utils/common';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { Card, Badge, Spinner, Button } from '../../components/ui';
import { Link, useNavigate } from 'react-router-dom';
import { FolderKanban, Box, Layers, Plus, ArrowRight, Play } from 'lucide-react';

export default function DashboardPage() {
  const { user } = useAuth();
  const { error } = useToast();
  const navigate = useNavigate();
  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    projectService.list()
      .then(r => setProjects(r.data || []))
      .catch(err => error(extractError(err)))
      .finally(() => setLoading(false));
  }, [error]);

  const totalContainers  = projects.reduce((s, p) => s + (p.totalRunningContainers || 0), 0);
  const totalServices    = projects.reduce((s, p) => s + (p.images?.length || 0), 0);
  const activeProjects   = projects.filter(p => p.totalRunningContainers > 0).length;

  const stats = [
    {
      label: 'Total Projects',
      value: projects.length,
      icon: FolderKanban,
      color: 'var(--accent-blue)',
      bg: 'rgba(56,139,253,.1)',
      link: '/projects',
    },
    {
      label: 'Active Projects',
      value: activeProjects,
      icon: Play,
      color: 'var(--accent-green)',
      bg: 'rgba(63,185,80,.1)',
    },
    {
      label: 'Service Definitions',
      value: totalServices,
      icon: Layers,
      color: 'var(--accent-cyan)',
      bg: 'rgba(57,197,207,.1)',
    },
    {
      label: 'Running Containers',
      value: totalContainers,
      icon: Box,
      color: 'var(--accent-purple)',
      bg: 'rgba(163,113,247,.1)',
    },
  ];

  if (loading) return <div className="page-loader"><Spinner size="lg" /></div>;

  return (
    <div style={{ padding: '32px', maxWidth: 1200 }}>

      {/* Welcome Header */}
      <div style={{ marginBottom: 36 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <div>
            <h1 style={{ fontSize: '1.6rem', fontWeight: 800, marginBottom: 6, letterSpacing: '-.02em' }}>
              Welcome, <span className="gradient-text">{user?.username}</span>
            </h1>
            <p style={{ color: 'var(--text-muted)', fontSize: '.9rem' }}>
              Here's an overview of your container projects.
            </p>
          </div>
          <Button icon={Plus} onClick={() => navigate('/projects')}>
            New Project
          </Button>
        </div>
      </div>

      {/* Stats */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
        gap: 16,
        marginBottom: 32,
      }}>
        {stats.map(stat => (
          <StatCard key={stat.label} {...stat} />
        ))}
      </div>

      {/* Recent Projects */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 20 }}>
        <Card style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{
            padding: '18px 20px',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <div style={{ padding: 6, background: 'rgba(56,139,253,.1)', borderRadius: 8, color: 'var(--accent-blue)', display: 'flex' }}>
                <FolderKanban size={16} />
              </div>
              <h3 style={{ fontSize: '.95rem', fontWeight: 700 }}>My Projects</h3>
            </div>
            <Link
              to="/projects"
              style={{
                display: 'flex', alignItems: 'center', gap: 4,
                fontSize: '.8rem', color: 'var(--accent-blue)', textDecoration: 'none', fontWeight: 500,
              }}
            >
              View All <ArrowRight size={13} />
            </Link>
          </div>

          {projects.length === 0 ? (
            <EmptyProjects onNavigate={() => navigate('/projects')} />
          ) : (
            <div>
              {projects.slice(0, 8).map((project, idx) => (
                <ProjectRow
                  key={project.id}
                  project={project}
                  isLast={idx === Math.min(projects.length, 8) - 1}
                />
              ))}
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}

const StatCard = ({ label, value, icon: Icon, color, bg, link }) => {
  const content = (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      gap: 16,
      padding: '18px 20px',
      background: 'var(--bg-card)',
      border: '1px solid var(--border-subtle)',
      borderRadius: 'var(--radius-lg)',
      transition: 'border-color var(--transition)',
      cursor: link ? 'pointer' : 'default',
    }}
      onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--border-default)'; }}
      onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border-subtle)'; }}
    >
      <div style={{ padding: 10, background: bg, borderRadius: 10, color, flexShrink: 0 }}>
        <Icon size={20} />
      </div>
      <div>
        <div style={{ fontSize: '.72rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 4 }}>
          {label}
        </div>
        <div style={{ fontSize: '1.6rem', fontWeight: 800, lineHeight: 1, color: 'var(--text-primary)' }}>
          {value}
        </div>
      </div>
    </div>
  );

  if (link) return <Link to={link} style={{ textDecoration: 'none' }}>{content}</Link>;
  return content;
};

const ProjectRow = ({ project, isLast }) => {
  const isRunning = project.totalRunningContainers > 0;
  return (
    <Link
      to={`/projects/${project.id}`}
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '13px 20px',
        textDecoration: 'none',
        color: 'inherit',
        borderBottom: isLast ? 'none' : '1px solid var(--border-subtle)',
        transition: 'background var(--transition)',
      }}
      className="list-item-hover"
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div className={`health-dot ${isRunning ? 'running' : 'stopped'}`} />
        <div>
          <div style={{ fontWeight: 600, fontSize: '.9rem' }}>{project.name}</div>
          <div style={{ fontSize: '.72rem', color: 'var(--text-muted)', marginTop: 2 }}>
            {project.images?.length || 0} services
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <Badge variant={isRunning ? 'green' : 'gray'}>
          {isRunning ? `${project.totalRunningContainers} container` : 'Stopped'}
        </Badge>
        <ArrowRight size={14} style={{ color: 'var(--text-muted)' }} />
      </div>
    </Link>
  );
};

const EmptyProjects = ({ onNavigate }) => (
  <div style={{ textAlign: 'center', padding: '48px 20px', color: 'var(--text-muted)' }}>
    <FolderKanban size={40} style={{ opacity: 0.2, marginBottom: 12 }} />
    <p style={{ marginBottom: 16, fontSize: '.9rem' }}>You haven't created any projects yet.</p>
    <Button variant="ghost" icon={Plus} onClick={onNavigate}>Create First Project</Button>
  </div>
);
