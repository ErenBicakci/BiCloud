import React, { useState, useEffect } from 'react';
import { containerService } from '../../services/container.service';
import { extractError } from '../../utils/common';
import { Card, Badge, Button, Spinner } from './index';
import {
  Search, X, Filter, ArrowUpDown, ArrowUp, ArrowDown,
  Terminal, Square, Trash2, Copy, Check,
  ChevronsLeft, ChevronsRight, ChevronLeft, Box,
} from 'lucide-react';

const STATUS_FILTERS = [
  { value: 'ALL',     label: 'All',      color: 'var(--text-secondary)' },
  { value: 'RUNNING', label: 'Running',  color: 'var(--accent-green)' },
  { value: 'FAILED',  label: 'Failed',   color: 'var(--accent-red)' },
  { value: 'STOPPED', label: 'Stopped',  color: 'var(--text-muted)' },
  { value: 'PENDING', label: 'Pending',  color: 'var(--accent-yellow)' },
];

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100];

/**
 * Server-side filtered, sorted, paginated container list.
 * Used by both ServiceDetailPage (single service) and ProjectDetailPage (all services).
 *
 * Props:
 *   projectId   – required
 *   serviceName – optional; if provided, only shows containers for that service
 *   reloadKey   – when changed, triggers a re-fetch
 *   onLogs      – (container) => void
 *   onStop      – (instanceId) => void
 *   onRemove    – (instanceId) => void
 */
export const ContainersPanel = ({ projectId, serviceName, reloadKey, onLogs, onStop, onRemove }) => {
  const [search, setSearch]       = useState('');
  const [debouncedSearch, setDeb] = useState('');
  const [statusFilter, setFilter] = useState('ALL');
  const [sortKey, setSortKey]     = useState('createdAt');
  const [sortDir, setSortDir]     = useState('desc');
  const [page, setPage]           = useState(0);
  const [pageSize, setPageSize]   = useState(25);

  const [data, setData]     = useState({
    content: [], page: 0, size: 25, totalElements: 0, totalPages: 0, statusCounts: {},
  });
  const [loading, setLoading]   = useState(true);
  const [fetchError, setError]  = useState(null);

  // Debounce search input -> 350ms
  useEffect(() => {
    const t = setTimeout(() => setDeb(search.trim()), 350);
    return () => clearTimeout(t);
  }, [search]);

  // Fetch
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    containerService.search(projectId, {
      serviceName: serviceName || undefined,
      status: statusFilter === 'ALL' ? undefined : statusFilter,
      search: debouncedSearch || undefined,
      sortBy: sortKey,
      sortDir,
      page,
      size: pageSize,
    }).then(res => {
      if (cancelled) return;
      setData(res.data);
    }).catch(err => {
      if (cancelled) return;
      setError(extractError(err));
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [projectId, serviceName, statusFilter, debouncedSearch, sortKey, sortDir, page, pageSize, reloadKey]);

  // Reset page when filter changes
  useEffect(() => { setPage(0); }, [debouncedSearch, statusFilter, pageSize]);

  const toggleSort = (key) => {
    if (sortKey === key) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortKey(key);
      setSortDir(key === 'createdAt' ? 'desc' : 'asc');
    }
  };

  const counts   = data.statusCounts || {};
  const totalAll = (counts.RUNNING || 0) + (counts.FAILED || 0) + (counts.STOPPED || 0) + (counts.PENDING || 0);

  return (
    <Card style={{ padding: 0 }}>
      {/* Header */}
      <div style={{
        padding: '18px 20px', borderBottom: '1px solid var(--border-subtle)',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.95rem', fontWeight: 700 }}>
          <Box size={16} color="var(--text-secondary)" />
          Container Instances
          <span style={{ marginLeft: 6, color: 'var(--text-muted)', fontWeight: 500, fontSize: '0.75rem' }}>
            ({data.totalElements}{data.totalElements !== totalAll && ` / ${totalAll}`})
          </span>
          {loading && <Spinner />}
        </div>

        {/* Arama */}
        <div style={{ position: 'relative', minWidth: 240 }}>
          <Search size={14} style={{
            position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)',
            color: 'var(--text-muted)', pointerEvents: 'none',
          }} />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Container ID, worker, port..."
            className="input"
            style={{ paddingLeft: 32, paddingRight: search ? 32 : 12, fontSize: '0.82rem' }}
          />
          {search && (
            <button onClick={() => setSearch('')} style={{
              position: 'absolute', right: 6, top: '50%', transform: 'translateY(-50%)',
              background: 'transparent', border: 'none', cursor: 'pointer',
              color: 'var(--text-muted)', padding: 4, display: 'inline-flex',
            }} title="Clear"><X size={14} /></button>
          )}
        </div>
      </div>

      {/* Filter chips */}
      <div style={{
        padding: '12px 20px', borderBottom: '1px solid var(--border-subtle)',
        display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center',
      }}>
        <Filter size={14} color="var(--text-muted)" style={{ marginRight: 4 }} />
        {STATUS_FILTERS.map(f => {
          const count  = f.value === 'ALL' ? totalAll : (counts[f.value] || 0);
          const active = statusFilter === f.value;
          return (
            <button
              key={f.value}
              onClick={() => setFilter(f.value)}
              style={{
                padding: '5px 12px', borderRadius: 999,
                background: active ? `${f.color}22` : 'transparent',
                border: `1px solid ${active ? f.color : 'var(--border-subtle)'}`,
                color: active ? f.color : 'var(--text-secondary)',
                cursor: 'pointer', fontSize: '0.78rem', fontWeight: 600,
                display: 'inline-flex', alignItems: 'center', gap: 6,
                transition: 'all .15s',
              }}
            >
              {f.label}
              <span style={{
                fontSize: '0.7rem', padding: '1px 6px', borderRadius: 999,
                background: active ? `${f.color}33` : 'var(--bg-elevated)',
                color: active ? f.color : 'var(--text-muted)', fontWeight: 600,
              }}>{count}</span>
            </button>
          );
        })}
      </div>

      {/* Content */}
      {fetchError ? (
        <div style={{ padding: 32, textAlign: 'center', color: 'var(--accent-red)', fontSize: '0.85rem' }}>
          Failed to fetch data: {fetchError}
        </div>
      ) : data.content.length === 0 && !loading ? (
        <div style={{ padding: 48, textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.85rem' }}>
          {totalAll === 0
            ? 'No containers for this project. Start a deploy operation.'
            : 'No containers match the current filter.'}
        </div>
      ) : (
        <>
          <div className="table-wrap" style={{ opacity: loading ? 0.55 : 1, transition: 'opacity .15s' }}>
            <table>
              <thead>
                <tr>
                  <SortableTh label="Status"      active={sortKey === 'status'}       dir={sortDir} onClick={() => toggleSort('status')} />
                  <th>Container ID</th>
                  {!serviceName && <th>Service</th>}
                  <SortableTh label="Worker"      active={sortKey === 'workerName'}   dir={sortDir} onClick={() => toggleSort('workerName')} />
                  <th>Gateway URL</th>
                  <th>CPU</th>
                  <th>RAM</th>
                  <SortableTh label="Created"      active={sortKey === 'createdAt'}    dir={sortDir} onClick={() => toggleSort('createdAt')} />
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map(c => (
                  <tr key={c.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <div className={`health-dot ${c.status?.toLowerCase()}`} />
                        <Badge variant={c.status === 'RUNNING' ? 'green' : (c.status === 'FAILED' ? 'red' : 'gray')}>
                          {c.status}
                        </Badge>
                      </div>
                    </td>
                    <td className="mono" style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                      {c.dockerContainerId ? c.dockerContainerId.substring(0, 12) : '—'}
                    </td>
                    {!serviceName && (
                      <td style={{ fontWeight: 600, fontSize: '0.82rem' }}>{c.serviceName}</td>
                    )}
                    <td>
                      <div style={{ display: 'flex', flexDirection: 'column' }}>
                        <span style={{ fontSize: '0.82rem', fontWeight: 500 }}>{c.workerName}</span>
                        {c.workerStatus && (
                          <span style={{ fontSize: '0.7rem', color: c.workerStatus === 'ACTIVE' ? 'var(--accent-green)' : 'var(--text-muted)' }}>
                            {c.workerStatus}
                          </span>
                        )}
                      </div>
                    </td>
                    <td><GatewayUrlCell url={c.gatewayUrl} /></td>
                    <td className="mono" style={{ fontSize: '0.78rem' }}>
                      {c.cpuPercent != null ? `${c.cpuPercent.toFixed(1)}%` : '—'}
                    </td>
                    <td><UsageCell used={c.memoryUsedMb} limit={c.memoryLimitMb} /></td>
                    <td style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }} title={formatDate(c.createdAt)}>
                      {formatRelative(c.createdAt)}
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                        <Button size="sm" variant="ghost" icon={Terminal} onClick={() => onLogs(c)}>Logs</Button>
                        {c.status === 'RUNNING' && (
                          <Button size="sm" variant="danger" icon={Square} onClick={() => onStop(c.id)} />
                        )}
                        <Button size="sm" variant="danger" icon={Trash2} onClick={() => onRemove(c.id)} />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <PaginationFooter
            page={data.page + 1}
            totalPages={Math.max(1, data.totalPages)}
            pageSize={pageSize}
            onPageChange={uiPage => setPage(Math.max(0, uiPage - 1))}
            onPageSizeChange={setPageSize}
            totalItems={data.totalElements}
            from={data.totalElements === 0 ? 0 : data.page * data.size + 1}
            to={Math.min((data.page + 1) * data.size, data.totalElements)}
          />
        </>
      )}
    </Card>
  );
};

/* ─── Sub-components ──────────────────────────────────────────────────────── */

const SortableTh = ({ label, active, dir, onClick }) => {
  const Icon = !active ? ArrowUpDown : (dir === 'asc' ? ArrowUp : ArrowDown);
  return (
    <th onClick={onClick} style={{ cursor: 'pointer', userSelect: 'none', color: active ? 'var(--text-primary)' : undefined }}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        {label}
        <Icon size={12} color={active ? 'var(--accent-blue)' : 'var(--text-muted)'} />
      </span>
    </th>
  );
};

const PaginationFooter = ({ page, totalPages, pageSize, onPageChange, onPageSizeChange, totalItems, from, to }) => (
  <div style={{
    padding: '12px 20px', borderTop: '1px solid var(--border-subtle)',
    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
    fontSize: '0.78rem', color: 'var(--text-muted)', flexWrap: 'wrap', gap: 12,
  }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span>Page size:</span>
      <select
        value={pageSize}
        onChange={e => onPageSizeChange(parseInt(e.target.value))}
        className="input"
        style={{ padding: '4px 8px', fontSize: '0.78rem', width: 'auto' }}
      >
        {PAGE_SIZE_OPTIONS.map(s => <option key={s} value={s}>{s}</option>)}
      </select>
      <span style={{ marginLeft: 12 }}>
        <strong style={{ color: 'var(--text-primary)' }}>{from}-{to}</strong> / {totalItems}
      </span>
    </div>
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <PagerBtn disabled={page === 1}          onClick={() => onPageChange(1)}           icon={ChevronsLeft}  title="First" />
      <PagerBtn disabled={page === 1}          onClick={() => onPageChange(page - 1)}    icon={ChevronLeft}   title="Previous" />
      <span style={{ padding: '0 10px', fontWeight: 600, color: 'var(--text-primary)' }}>{page} / {totalPages}</span>
      <PagerBtn disabled={page === totalPages} onClick={() => onPageChange(page + 1)}    icon={ChevronLeft}   rotate title="Next" />
      <PagerBtn disabled={page === totalPages} onClick={() => onPageChange(totalPages)}  icon={ChevronsRight} title="Last" />
    </div>
  </div>
);

const PagerBtn = ({ disabled, onClick, icon: Icon, rotate, title }) => (
  <button
    disabled={disabled}
    onClick={onClick}
    title={title}
    style={{
      background: 'transparent', border: '1px solid var(--border-subtle)',
      borderRadius: 6, width: 28, height: 28,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      color: disabled ? 'var(--text-muted)' : 'var(--text-primary)',
      cursor: disabled ? 'not-allowed' : 'pointer',
      opacity: disabled ? 0.4 : 1, transition: 'all .15s',
    }}
  >
    <Icon size={14} style={rotate ? { transform: 'rotate(180deg)' } : undefined} />
  </button>
);

const UsageCell = ({ used, limit }) => {
  if (used == null) return <span style={{ color: 'var(--text-muted)' }}>—</span>;
  const ratio    = limit > 0 ? Math.min(used / limit, 1) : 0;
  const barColor = ratio > 0.85 ? 'var(--accent-red)' : ratio > 0.6 ? 'var(--accent-yellow)' : 'var(--accent-green)';
  return (
    <div style={{ minWidth: 90 }}>
      <div className="mono" style={{ fontSize: '0.75rem', marginBottom: 3 }}>
        {used} MB{limit > 0 && <span style={{ color: 'var(--text-muted)' }}> / {limit}</span>}
      </div>
      {limit > 0 && (
        <div style={{ height: 3, borderRadius: 2, background: 'var(--bg-elevated)', overflow: 'hidden' }}>
          <div style={{
            width: `${(ratio * 100).toFixed(0)}%`, height: '100%',
            background: barColor, borderRadius: 2, transition: 'width .3s',
          }} />
        </div>
      )}
    </div>
  );
};

const GatewayUrlCell = ({ url }) => {
  const [copied, setCopied] = React.useState(false);
  if (!url) return <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>—</span>;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <code className="mono" style={{
        fontSize: '0.72rem', color: 'var(--accent-cyan)',
        maxWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'block',
      }} title={url}>{url}</code>
      <button
        onClick={() => { navigator.clipboard.writeText(url); setCopied(true); setTimeout(() => setCopied(false), 1500); }}
        style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', padding: 3, display: 'inline-flex' }}
        title="Copy"
      >
        {copied ? <Check size={12} color="var(--accent-green)" /> : <Copy size={12} />}
      </button>
    </div>
  );
};

/* ─── Helper functions ───────────────────────────────────────────────────── */

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('en-US', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    });
  } catch { return iso; }
}

function formatRelative(iso) {
  if (!iso) return '—';
  const diffSec = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diffSec < 60)    return `${Math.floor(diffSec)}s ago`;
  if (diffSec < 3600)  return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}
