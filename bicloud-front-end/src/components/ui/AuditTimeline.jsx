import React, { useState, useEffect, useCallback } from 'react';
import { Spinner } from './index';
import {
  FolderPlus, FolderX, Rocket, Square, Plus, PenLine, Trash2, SlidersHorizontal,
  HeartPulse, AlertTriangle, RotateCw, Ghost, ChevronsDown, WifiOff, Wrench,
  Activity, User, Cpu,
} from 'lucide-react';

/**
 * Event type -> icon, color, human-readable label.
 * Color variables are compatible with the theme (var(--accent-*)).
 */
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

/**
 * Reusable audit timeline component.
 *
 * Props:
 *   fetcher(params)  – function that accepts { action?, beforeId?, limit } and returns an axios response
 *   pageSize         – events per page (default 30)
 *   reloadKey        – when changed, reloads from the beginning
 *   compact          – more compact embedded view (for project detail)
 *   emptyText        – text shown when there are no events
 */
export const AuditTimeline = ({ fetcher, pageSize = 30, reloadKey = 0, compact = false, emptyText }) => {
  const [events, setEvents]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError]     = useState(null);

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

  useEffect(() => { loadInitial(); }, [loadInitial, reloadKey]);

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
      .catch(() => { /* silently ignore; existing list stays */ })
      .finally(() => setLoadingMore(false));
  };

  if (loading) {
    return <div style={{ padding: 40, textAlign: 'center' }}><Spinner size="lg" /></div>;
  }
  if (error) {
    return <div style={{ padding: 32, textAlign: 'center', color: 'var(--accent-red)', fontSize: '0.85rem' }}>{error}</div>;
  }
  if (events.length === 0) {
    return (
      <div style={{ padding: compact ? 24 : 48, textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.85rem' }}>
        {emptyText || 'No activity recorded yet.'}
      </div>
    );
  }

  return (
    <div>
      <div style={{ position: 'relative' }}>
        {/* Vertical line */}
        <div style={{
          position: 'absolute', left: compact ? 15 : 17, top: 8, bottom: 8,
          width: 2, background: 'var(--border-subtle)',
        }} />
        {events.map(ev => <TimelineRow key={ev.id} ev={ev} compact={compact} />)}
      </div>

      {hasMore && (
        <div style={{ textAlign: 'center', marginTop: 16 }}>
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

const TimelineRow = ({ ev, compact }) => {
  const meta = ACTION_META[ev.action] || FALLBACK_META;
  const Icon = meta.icon;
  const isSystem = ev.actorType === 'SYSTEM';
  const isWarn   = ev.severity === 'WARN';
  const dot = compact ? 32 : 36;

  return (
    <div style={{ display: 'flex', gap: compact ? 12 : 16, paddingBottom: compact ? 14 : 18, position: 'relative' }}>
      {/* Icon circle */}
      <div style={{
        flexShrink: 0, width: dot, height: dot, borderRadius: '50%',
        background: 'var(--bg-surface)',
        border: `1.5px solid ${meta.color}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: meta.color, zIndex: 1,
        boxShadow: isWarn ? `0 0 0 4px ${meta.color}18` : 'none',
      }}>
        <Icon size={compact ? 15 : 17} />
      </div>

      {/* Content */}
      <div style={{ flex: 1, minWidth: 0, paddingTop: 2 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            <span style={{ fontWeight: 600, fontSize: compact ? '0.82rem' : '0.88rem', color: meta.color }}>
              {meta.label}
            </span>
            {ev.targetName && (
              <span className="mono truncate" style={{ fontSize: '0.76rem', color: 'var(--text-secondary)' }}>
                {ev.targetName}
              </span>
            )}
          </div>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', flexShrink: 0 }}
                title={formatAbsolute(ev.createdAt)}>
            {formatRelative(ev.createdAt)}
          </span>
        </div>

        {ev.message && (
          <div style={{ fontSize: '0.76rem', color: 'var(--text-muted)', marginTop: 3, lineHeight: 1.45 }}>
            {ev.message}
          </div>
        )}

        {/* Actor rozeti */}
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 5, marginTop: 5,
                      fontSize: '0.68rem', color: isSystem ? 'var(--accent-purple, #a371f7)' : 'var(--text-muted)' }}>
          {isSystem ? <Cpu size={11} /> : <User size={11} />}
          {isSystem ? `sistem · ${ev.actorName}` : ev.actorName}
        </div>
      </div>
    </div>
  );
};

function formatRelative(iso) {
  if (!iso) return '—';
  const diffSec = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diffSec < 60)    return `${Math.floor(diffSec)}s ago`;
  if (diffSec < 3600)  return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

function formatAbsolute(iso) {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleString('en-US', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
  } catch { return iso; }
}
