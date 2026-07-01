import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Modal } from './Modal';
import { Spinner } from './index';
import { containerService } from '../../services/container.service';
import { extractError } from '../../utils/common';
import { Play, Pause } from 'lucide-react';

const TAIL_OPTIONS = [50, 100, 200, 500, 1000];
const FOLLOW_INTERVAL_MS = 3000;

/**
 * Shared container log modal.
 *
 * Features:
 *  - Tail selector (last N lines)
 *  - "Follow" mode: re-fetches every 3s and auto-scrolls to the bottom
 *
 * Used by both ServiceDetailPage and ProjectDetailPage.
 */
export const LogsModal = ({ instance, onClose }) => {
  // Don't mount while closed: state resets fresh on every open
  if (!instance) return null;
  return <LogsDialog instance={instance} onClose={onClose} />;
};

const LogsDialog = ({ instance, onClose }) => {
  const [logs, setLogs]       = useState('');
  const [loading, setLoading] = useState(true); // only for the initial load
  const [tail, setTail]       = useState(200);
  const [follow, setFollow]   = useState(false);
  const boxRef = useRef(null);

  const fetchLogs = useCallback(() => {
    return containerService.logs(instance.id, tail)
      .then(r => setLogs(r.data.logs || '(No logs)'))
      .catch(e => setLogs(`Error: ${extractError(e)}`))
      .finally(() => setLoading(false));
  }, [instance.id, tail]);

  // Re-fetch on initial load and when tail changes
  useEffect(() => { fetchLogs(); }, [fetchLogs]);

  // Follow mode: periodic silent refresh
  useEffect(() => {
    if (!follow) return;
    const t = setInterval(fetchLogs, FOLLOW_INTERVAL_MS);
    return () => clearInterval(t);
  }, [follow, fetchLogs]);

  // Scroll to bottom on every update when following
  useEffect(() => {
    if (follow && boxRef.current) {
      boxRef.current.scrollTop = boxRef.current.scrollHeight;
    }
  }, [logs, follow]);

  const title = `Logs: ${instance.serviceName}` +
    (instance.dockerContainerId ? ` • ${instance.dockerContainerId.substring(0, 12)}` : '');

  return (
    <Modal isOpen onClose={onClose} title={title} maxWidth={900}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12, flexWrap: 'wrap' }}>
        <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Last N lines:</span>
        <div style={{ display: 'flex', gap: 6 }}>
          {TAIL_OPTIONS.map(n => (
            <button
              key={n}
              onClick={() => setTail(n)}
              style={{
                padding: '3px 10px', borderRadius: 6, fontSize: '0.75rem', fontWeight: 600,
                border: `1px solid ${tail === n ? 'var(--accent-blue)' : 'var(--border-subtle)'}`,
                background: tail === n ? 'rgba(56,139,253,.15)' : 'transparent',
                color: tail === n ? 'var(--accent-blue)' : 'var(--text-muted)',
                cursor: 'pointer', transition: 'all .15s',
              }}
            >{n}</button>
          ))}
        </div>

        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10 }}>
          {loading && <Spinner />}
          <button
            onClick={() => setFollow(f => !f)}
            title={follow ? 'Stop following' : 'Live follow: refresh every 3s and scroll to bottom'}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 6,
              padding: '4px 12px', borderRadius: 6, fontSize: '0.78rem', fontWeight: 600,
              border: `1px solid ${follow ? 'var(--accent-green)' : 'var(--border-subtle)'}`,
              background: follow ? 'rgba(63,185,80,.12)' : 'transparent',
              color: follow ? 'var(--accent-green)' : 'var(--text-secondary)',
              cursor: 'pointer', transition: 'all .15s',
            }}
          >
            {follow
              ? <><span className="health-dot running" style={{ width: 7, height: 7 }} /> Following <Pause size={12} /></>
              : <><Play size={12} /> Follow</> }
          </button>
        </div>
      </div>

      <div ref={boxRef} className="code-block" style={{ height: 480, fontSize: '0.75rem', overflowY: 'auto' }}>
        {loading ? '' : logs}
      </div>
    </Modal>
  );
};
