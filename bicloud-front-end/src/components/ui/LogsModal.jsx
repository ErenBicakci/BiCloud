import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Modal } from './Modal';
import { Spinner } from './index';
import { containerService } from '../../services/container.service';
import { extractError } from '../../utils/common';
import { Check, Copy, Pause, Play, RefreshCw, Terminal } from 'lucide-react';

const TAIL_OPTIONS = [50, 100, 200, 500, 1000];
const FOLLOW_INTERVAL_MS = 3000;

export const LogsModal = ({ instance, onClose }) => {
  if (!instance) return null;
  return <LogsDialog instance={instance} onClose={onClose} />;
};

const LogsDialog = ({ instance, onClose }) => {
  const [logs, setLogs] = useState('');
  const [loading, setLoading] = useState(true);
  const [tail, setTail] = useState(200);
  const [follow, setFollow] = useState(false);
  const [copied, setCopied] = useState(false);
  const boxRef = useRef(null);

  const fetchLogs = useCallback((silent = false) => {
    if (!silent) setLoading(true);
    return containerService.logs(instance.id, tail)
      .then(r => setLogs(r.data.logs || 'No logs returned.'))
      .catch(e => setLogs(`Error: ${extractError(e)}`))
      .finally(() => setLoading(false));
  }, [instance.id, tail]);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) fetchLogs();
    });
    return () => { cancelled = true; };
  }, [fetchLogs]);

  useEffect(() => {
    if (!follow) return undefined;
    const t = setInterval(() => fetchLogs(true), FOLLOW_INTERVAL_MS);
    return () => clearInterval(t);
  }, [follow, fetchLogs]);

  useEffect(() => {
    if (follow && boxRef.current) {
      boxRef.current.scrollTop = boxRef.current.scrollHeight;
    }
  }, [logs, follow]);

  const copyLogs = () => {
    navigator.clipboard.writeText(logs);
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  };

  const containerId = instance.dockerContainerId?.substring(0, 12);
  const lines = loading ? [] : logs.split(/\r?\n/);

  return (
    <Modal
      isOpen
      onClose={onClose}
      maxWidth={960}
      title={(
        <span className="log-modal-title">
          <Terminal size={17} color="var(--accent-blue)" />
          <span>Logs / {instance.serviceName}</span>
          {containerId && <span className="badge badge-gray mono">{containerId}</span>}
        </span>
      )}
    >
      <div className="log-toolbar">
        <div className="segmented" aria-label="Tail lines">
          {TAIL_OPTIONS.map(n => (
            <button
              key={n}
              className={tail === n ? 'active' : ''}
              onClick={() => setTail(n)}
            >
              {n}
            </button>
          ))}
        </div>

        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          {loading && <Spinner />}
          <button className="btn btn-ghost btn-sm" onClick={() => fetchLogs()} disabled={loading}>
            <RefreshCw size={13} /> Refresh
          </button>
          <button className="btn btn-ghost btn-sm" onClick={copyLogs} disabled={loading}>
            {copied ? <Check size={13} /> : <Copy size={13} />} Copy
          </button>
          <button
            className={`btn btn-sm ${follow ? 'btn-success' : 'btn-ghost'}`}
            onClick={() => setFollow(f => !f)}
          >
            {follow ? <Pause size={13} /> : <Play size={13} />}
            {follow ? 'Following' : 'Follow'}
          </button>
        </div>
      </div>

      <div ref={boxRef} className="log-viewer">
        {loading ? null : lines.map((line, index) => (
          <div className="log-line" key={`${index}-${line}`}>
            <span className="log-line-no">{index + 1}</span>
            <code>{line || ' '}</code>
          </div>
        ))}
      </div>
    </Modal>
  );
};
