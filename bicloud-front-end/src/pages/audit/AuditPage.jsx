import React, { useState, useCallback } from 'react';
import { auditService } from '../../services/audit.service';
import { useAuth } from '../../context/AuthContext';
import { AuditTimeline } from '../../components/ui/AuditTimeline';
import { Button } from '../../components/ui';
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
    <div className="page">
      <header className="page-header">
        <div>
          <div className="page-kicker"><History size={14} /> Audit Trail</div>
          <h1 className="page-title">Activity</h1>
          <p className="page-subtitle">
            {isAdmin
              ? 'All user and automation events across the system.'
              : 'User actions and self-healing events in your projects.'}
          </p>
        </div>
        <div className="page-actions">
          <Button variant="ghost" icon={RefreshCw} onClick={() => setReload(k => k + 1)}>
            Refresh
          </Button>
        </div>
      </header>

      <section className="page-surface">
        <div className="panel-header">
          <div>
            <div className="panel-title"><History size={16} /> Event stream</div>
            <div className="panel-subtitle">Filtered operational activity, newest first</div>
          </div>
          <div className="filter-pills">
            {AUDIT_FILTERS.map(f => (
              <button
                key={f.value}
                className={`filter-pill ${filter === f.value ? 'active' : ''}`}
                onClick={() => setFilter(f.value)}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>
        <AuditTimeline fetcher={fetcher} reloadKey={reloadKey} />
      </section>
    </div>
  );
}
