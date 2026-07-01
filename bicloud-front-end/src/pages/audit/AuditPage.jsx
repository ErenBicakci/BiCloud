import React, { useState, useCallback } from 'react';
import { auditService } from '../../services/audit.service';
import { useAuth } from '../../context/AuthContext';
import { AuditTimeline } from '../../components/ui/AuditTimeline';
import { Card } from '../../components/ui';
import { History, RefreshCw } from 'lucide-react';

const AUDIT_FILTERS = [
  { value: 'ALL',                 label: 'All' },
  { value: 'PROJECT_DEPLOYED',    label: 'Deploy' },
  { value: 'SERVICE_SCALED',      label: 'Scaling' },
  { value: 'SELF_HEALING_DEPLOY', label: 'Self-healing' },
  { value: 'CRASH_LOOP_DETECTED', label: 'Crash-loop' },
  { value: 'WORKER_OFFLINE',      label: 'Worker offline' },
];

export default function AuditPage() {
  const { isAdmin } = useAuth();
  const [filter, setFilter]     = useState('ALL');
  const [reloadKey, setReload]  = useState(0);

  // Fetcher includes the selected filter; when filter changes, useCallback refreshes ->
  // AuditTimeline reloads from the beginning.
  const fetcher = useCallback(
    (params) => auditService.feed({
      ...params,
      action: filter === 'ALL' ? undefined : filter,
    }),
    [filter]
  );

  return (
    <div style={{ padding: '32px', maxWidth: 860 }}>
      {/* Header */}
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 28 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
            <div style={{
              padding: 8, borderRadius: 10,
              background: 'rgba(56,139,253,.12)', border: '1px solid rgba(56,139,253,.25)',
              color: 'var(--accent-blue)', display: 'flex',
            }}>
              <History size={18} />
            </div>
            <h1 style={{ fontSize: '1.5rem', fontWeight: 800 }}>Activity</h1>
          </div>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
            {isAdmin
              ? 'All user and automation events across the system.'
              : 'User actions and self-healing events in your projects.'}
          </p>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={() => setReload(k => k + 1)}
                style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <RefreshCw size={13} /> Refresh
        </button>
      </header>

      {/* Filter chips */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 20 }}>
        {AUDIT_FILTERS.map(f => {
          const active = filter === f.value;
          return (
            <button
              key={f.value}
              onClick={() => setFilter(f.value)}
              style={{
                padding: '5px 14px', borderRadius: 999, fontSize: '0.78rem', fontWeight: 600,
                border: `1px solid ${active ? 'var(--accent-blue)' : 'var(--border-subtle)'}`,
                background: active ? 'rgba(56,139,253,.15)' : 'transparent',
                color: active ? 'var(--accent-blue)' : 'var(--text-secondary)',
                cursor: 'pointer', transition: 'all .15s',
              }}
            >{f.label}</button>
          );
        })}
      </div>

      <Card>
        <AuditTimeline fetcher={fetcher} reloadKey={reloadKey} />
      </Card>
    </div>
  );
}
