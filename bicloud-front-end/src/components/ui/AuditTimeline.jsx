import React, { useState, useEffect, useCallback } from 'react';
import { Spinner } from './index';
import {
  Activity,
  AlertTriangle,
  ChevronsDown,
  Cpu,
  FolderPlus,
  FolderX,
  Ghost,
  HeartPulse,
  Plus,
  PenLine,
  Rocket,
  RotateCw,
  SlidersHorizontal,
  Square,
  Trash2,
  User,
  WifiOff,
  Wrench,
} from 'lucide-react';

const ACTION_META = {
  PROJECT_CREATED:     { icon: FolderPlus,        color: 'var(--accent-blue)',   label: 'Project created' },
  PROJECT_DELETED:     { icon: FolderX,           color: 'var(--accent-red)',    label: 'Project deleted' },
  PROJECT_DEPLOYED:    { icon: Rocket,            color: 'var(--accent-green)',  label: 'Project deployed' },
  PROJECT_UNDEPLOYED:  { icon: Square,            color: 'var(--text-muted)',    label: 'Project stopped' },
  SERVICE_CREATED:     { icon: Plus,              color: 'var(--accent-blue)',   label: 'Service added' },
  SERVICE_UPDATED:     { icon: PenLine,           color: 'var(--accent-blue)',   label: 'Service updated' },
  SERVICE_DELETED:     { icon: Trash2,            color: 'var(--accent-red)',    label: 'Service deleted' },
  SERVICE_SCALED:      { icon: SlidersHorizontal, color: 'var(--accent-cyan)',   label: 'Scaled' },
  CONTAINER_STOPPED:   { icon: Square,            color: 'var(--accent-yellow)', label: 'Container stopped' },
  CONTAINER_REMOVED:   { icon: Trash2,            color: 'var(--accent-red)',    label: 'Container deleted' },
  SELF_HEALING_DEPLOY: { icon: HeartPulse,        color: 'var(--accent-yellow)', label: 'Self-healing' },
  CRASH_LOOP_DETECTED: { icon: AlertTriangle,     color: 'var(--accent-red)',    label: 'Crash-loop' },
  CONTAINER_RECOVERED: { icon: RotateCw,          color: 'var(--accent-green)',  label: 'Container recovered' },
  ZOMBIE_DETECTED:     { icon: Ghost,             color: 'var(--accent-yellow)', label: 'Zombie detected' },
  EXCESS_SCALED_DOWN:  { icon: ChevronsDown,      color: 'var(--accent-cyan)',   label: 'Excess replicas scaled down' },
  WORKER_OFFLINE:      { icon: WifiOff,           color: 'var(--accent-red)',    label: 'Worker offline' },
  WORKER_MAINTENANCE:  { icon: Wrench,            color: 'var(--accent-yellow)', label: 'Worker maintenance' },
};

const FALLBACK_META = { icon: Activity, color: 'var(--text-muted)', label: 'Event' };

export const AuditTimeline = ({ fetcher, pageSize = 30, reloadKey = 0, compact = false, emptyText }) => {
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState(null);

  const loadInitial = useCallback(() => {
    setLoading(true);
    setError(null);
    fetcher({ limit: pageSize })
      .then(res => {
        const list = res.data || [];
        setEvents(list);
        setHasMore(list.length === pageSize);
      })
      .catch(() => setError('Failed to load activity.'))
      .finally(() => setLoading(false));
  }, [fetcher, pageSize]);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) loadInitial();
    });
    return () => { cancelled = true; };
  }, [loadInitial, reloadKey]);

  const loadMore = () => {
    if (events.length === 0) return;
    const beforeId = events[events.length - 1].id;
    setLoadingMore(true);
    fetcher({ limit: pageSize, beforeId })
      .then(res => {
        const list = res.data || [];
        setEvents(prev => [...prev, ...list]);
        setHasMore(list.length === pageSize);
      })
      .catch(() => {})
      .finally(() => setLoadingMore(false));
  };

  if (loading) {
    return <div style={{ padding: 40, textAlign: 'center' }}><Spinner size="lg" /></div>;
  }
  if (error) {
    return <div style={{ padding: 32, textAlign: 'center', color: 'var(--accent-red)', fontSize: '.85rem' }}>{error}</div>;
  }
  if (events.length === 0) {
    return (
      <div style={{ padding: compact ? 24 : 48, textAlign: 'center', color: 'var(--text-muted)', fontSize: '.85rem' }}>
        {emptyText || 'No activity recorded yet.'}
      </div>
    );
  }

  return (
    <div>
      <div className="audit-list">
        {events.map(ev => <AuditRow key={ev.id} ev={ev} compact={compact} />)}
      </div>

      {hasMore && (
        <div style={{ textAlign: 'center', padding: '0 14px 16px' }}>
          <button
            onClick={loadMore}
            disabled={loadingMore}
            className="btn btn-ghost btn-sm"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
          >
            {loadingMore ? <Spinner /> : null}
            Load more
          </button>
        </div>
      )}
    </div>
  );
};

const AuditRow = ({ ev }) => {
  const meta = ACTION_META[ev.action] || FALLBACK_META;
  const isSystem = ev.actorType === 'SYSTEM';

  return (
    <div className="audit-event-row">
      <div className="audit-icon" style={{ color: meta.color }}>
        {React.createElement(meta.icon, { size: 17 })}
      </div>

      <div className="audit-main">
        <div className="audit-titleline">
          <span className="audit-title" style={{ color: meta.color }}>{meta.label}</span>
          {ev.targetName && <span className="audit-target mono truncate">{ev.targetName}</span>}
        </div>
        {ev.message && <div className="audit-message">{ev.message}</div>}
        <div className="audit-meta">
          <span>
            {isSystem ? React.createElement(Cpu, { size: 12 }) : React.createElement(User, { size: 12 })}
            {isSystem ? `system / ${ev.actorName || 'automation'}` : (ev.actorName || 'user')}
          </span>
          {ev.targetType && <span className="mono">{ev.targetType.toLowerCase()}</span>}
          {ev.severity === 'WARN' && <span style={{ color: 'var(--accent-yellow)' }}>warning</span>}
        </div>
      </div>

      <div className="audit-time" title={formatAbsolute(ev.createdAt)}>
        {formatRelative(ev.createdAt)}
      </div>
    </div>
  );
};

function formatRelative(iso) {
  if (!iso) return '-';
  const diffSec = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diffSec < 60) return `${Math.floor(diffSec)}s ago`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

function formatAbsolute(iso) {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleString('en-US', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return iso;
  }
}
