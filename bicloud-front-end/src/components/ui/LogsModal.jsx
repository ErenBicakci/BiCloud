import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Modal } from './Modal';
import { Spinner } from './index';
import { containerService } from '../../services/container.service';
import { extractError } from '../../utils/common';
import {
  ArrowDown,
  Check,
  Copy,
  Download,
  Filter,
  Pause,
  Play,
  RefreshCw,
  Search,
  Terminal,
  WrapText,
  X,
} from 'lucide-react';

const TAIL_OPTIONS = [50, 100, 200, 500, 1000];
const FOLLOW_INTERVAL_MS = 2500;

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
  const [searchTerm, setSearchTerm] = useState('');
  const [wrapLines, setWrapLines] = useState(true);
  const [autoScroll, setAutoScroll] = useState(true);
  const boxRef = useRef(null);

  const fetchLogs = useCallback((silent = false) => {
    if (!silent) setLoading(true);
    return containerService.logs(instance.id, tail)
      .then(r => {
        const text = r.data.logs || 'No logs returned.';
        setLogs(text);
      })
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

  // Live polling when follow is enabled
  useEffect(() => {
    if (!follow) return undefined;
    const t = setInterval(() => fetchLogs(true), FOLLOW_INTERVAL_MS);
    return () => clearInterval(t);
  }, [follow, fetchLogs]);

  // Handle auto-scroll to bottom
  useEffect(() => {
    if (autoScroll && boxRef.current) {
      boxRef.current.scrollTop = boxRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  // Detect manual scroll up to pause auto-scroll
  const handleScroll = () => {
    if (!boxRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = boxRef.current;
    const isAtBottom = scrollHeight - (scrollTop + clientHeight) < 30;
    setAutoScroll(isAtBottom);
  };

  const copyLogs = () => {
    navigator.clipboard.writeText(logs);
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  };

  const downloadLogs = () => {
    const blob = new Blob([logs], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${instance.serviceName || 'service'}-${instance.dockerContainerId?.substring(0, 12) || 'container'}.log`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const containerId = instance.dockerContainerId?.substring(0, 12);
  const rawLines = useMemo(() => (logs ? logs.split(/\r?\n/) : []), [logs]);

  // Filter lines by search term
  const { filteredLines, matchCount } = useMemo(() => {
    if (!searchTerm.trim()) {
      return { filteredLines: rawLines.map((line, idx) => ({ line, lineNo: idx + 1 })), matchCount: 0 };
    }
    const q = searchTerm.toLowerCase();
    const matches = [];
    rawLines.forEach((line, idx) => {
      if (line.toLowerCase().includes(q)) {
        matches.push({ line, lineNo: idx + 1 });
      }
    });
    return { filteredLines: matches, matchCount: matches.length };
  }, [rawLines, searchTerm]);

  // Format log level color styling
  const getLineClass = (line) => {
    const l = line.toUpperCase();
    if (l.includes('ERROR') || l.includes('FATAL') || l.includes('EXCEPTION') || l.includes('FAIL')) {
      return 'log-lvl-error';
    }
    if (l.includes('WARN') || l.includes('WARNING')) {
      return 'log-lvl-warn';
    }
    if (l.includes('INFO')) {
      return 'log-lvl-info';
    }
    if (l.includes('DEBUG') || l.includes('TRACE')) {
      return 'log-lvl-debug';
    }
    return '';
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      maxWidth={980}
      title={(
        <span className="log-modal-title">
          <Terminal size={17} color="var(--accent-blue)" />
          <span>Logs / {instance.serviceName}</span>
          {containerId && <span className="badge badge-gray mono">{containerId}</span>}
        </span>
      )}
    >
      <div className="log-toolbar">
        {/* Left: Tail lines & Search */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <div className="segmented" aria-label="Tail lines">
            {TAIL_OPTIONS.map(n => (
              <button
                key={n}
                type="button"
                className={tail === n ? 'active' : ''}
                onClick={() => setTail(n)}
                title={`Fetch last ${n} lines`}
              >
                {n}
              </button>
            ))}
          </div>

          <div className="log-search-wrap">
            <Search size={13} className="log-search-icon" />
            <input
              type="text"
              placeholder="Filter logs (grep)..."
              value={searchTerm}
              onChange={e => setSearchTerm(e.target.value)}
              className="input input-sm log-search-input"
            />
            {searchTerm && (
              <button
                type="button"
                className="btn-icon log-search-clear"
                onClick={() => setSearchTerm('')}
                aria-label="Clear filter"
              >
                <X size={13} />
              </button>
            )}
          </div>
          {searchTerm && (
            <span className="badge badge-blue" style={{ fontSize: '.72rem' }}>
              {matchCount} {matchCount === 1 ? 'match' : 'matches'}
            </span>
          )}
        </div>

        {/* Right: Actions */}
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
          {loading && <Spinner />}

          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => fetchLogs()}
            disabled={loading}
            title="Refresh logs"
          >
            <RefreshCw size={13} /> Refresh
          </button>

          <button
            type="button"
            className={`btn btn-sm ${wrapLines ? 'btn-ghost active' : 'btn-ghost'}`}
            onClick={() => setWrapLines(w => !w)}
            title={wrapLines ? 'Disable line wrapping' : 'Enable line wrapping'}
          >
            <WrapText size={13} /> {wrapLines ? 'Wrap' : 'No Wrap'}
          </button>

          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={copyLogs}
            disabled={loading}
            title="Copy all logs to clipboard"
          >
            {copied ? <Check size={13} color="var(--accent-green)" /> : <Copy size={13} />} Copy
          </button>

          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={downloadLogs}
            disabled={loading || !logs}
            title="Download log file"
          >
            <Download size={13} /> Download
          </button>

          <button
            type="button"
            className={`btn btn-sm ${follow ? 'btn-success' : 'btn-ghost'}`}
            onClick={() => {
              setFollow(f => !f);
              if (!follow) setAutoScroll(true);
            }}
            title={follow ? 'Stop live streaming' : 'Stream logs live'}
          >
            {follow ? <Pause size={13} /> : <Play size={13} />}
            {follow ? 'Streaming' : 'Stream'}
          </button>
        </div>
      </div>

      <div
        ref={boxRef}
        onScroll={handleScroll}
        className={`log-viewer ${wrapLines ? 'wrap-enabled' : 'wrap-disabled'}`}
      >
        {loading && rawLines.length === 0 ? (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', gap: 10 }}>
            <Spinner size="md" />
            <span style={{ color: 'var(--text-muted)' }}>Streaming container logs...</span>
          </div>
        ) : filteredLines.length === 0 ? (
          <div className="empty-state" style={{ padding: 30 }}>
            <p>No log lines matching "{searchTerm}"</p>
          </div>
        ) : (
          filteredLines.map(({ line, lineNo }) => {
            const lvlClass = getLineClass(line);
            return (
              <div className={`log-line ${lvlClass}`} key={`${lineNo}-${line}`}>
                <span className="log-line-no">{lineNo}</span>
                <code>{line || ' '}</code>
              </div>
            );
          })
        )}
      </div>

      {/* Auto-scroll anchor button if scrolled up */}
      {!autoScroll && (
        <button
          type="button"
          className="btn btn-primary btn-sm log-scroll-bottom-btn"
          onClick={() => {
            setAutoScroll(true);
            if (boxRef.current) {
              boxRef.current.scrollTop = boxRef.current.scrollHeight;
            }
          }}
        >
          <ArrowDown size={13} /> Jump to latest logs
        </button>
      )}
    </Modal>
  );
};

