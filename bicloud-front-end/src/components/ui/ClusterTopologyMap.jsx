import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge } from './index';
import {
  Box,
  Cloud,
  Cpu,
  Globe,
  MemoryStick,
  Radio,
  Server,
  Star,
} from 'lucide-react';

export const ClusterTopologyMap = ({ workers = [], projects = [] }) => {
  const navigate = useNavigate();

  const totalContainers = projects.reduce((s, p) => s + (p.totalRunningContainers || 0), 0);
  const activeWorkers = workers.filter(w => w.status === 'ACTIVE');

  return (
    <div className="topology-container">
      {/* Cluster Hub (Control Plane & Gateway) */}
      <div className="topology-hub-row">
        {/* Control Plane Node */}
        <div className="topology-hub-card">
          <div className="topology-hub-icon cp">
            <Cloud size={24} />
          </div>
          <div className="topology-hub-info">
            <div className="topology-hub-title">
              Control Plane
              <span className="health-dot running" style={{ width: 8, height: 8 }} />
            </div>
            <div className="topology-hub-sub">
              Orchestrator & Scheduler (Active)
            </div>
          </div>
          <div className="topology-hub-stats">
            <div className="topology-mini-stat">
              <span>Projects</span>
              <strong>{projects.length}</strong>
            </div>
            <div className="topology-mini-stat">
              <span>Containers</span>
              <strong>{totalContainers}</strong>
            </div>
          </div>
        </div>

        {/* Central Router / Gateway Node */}
        <div className="topology-hub-card gateway">
          <div className="topology-hub-icon gw">
            <Globe size={24} />
          </div>
          <div className="topology-hub-info">
            <div className="topology-hub-title">
              Dynamic Gateway
              <span className="health-dot running" style={{ width: 8, height: 8 }} />
            </div>
            <div className="topology-hub-sub">
              Dynamic & Mesh Routing Active
            </div>
          </div>
          <div className="topology-hub-stats">
            <div className="topology-mini-stat">
              <span>Active Nodes</span>
              <strong style={{ color: 'var(--accent-green)' }}>{activeWorkers.length}</strong>
            </div>
            <div className="topology-mini-stat">
              <span>Cluster State</span>
              <strong style={{ color: 'var(--accent-cyan)' }}>Healthy</strong>
            </div>
          </div>
        </div>
      </div>

      {/* Connection Bus */}
      <div className="topology-bus">
        <div className="topology-bus-line" />
        <div className="topology-bus-label">
          <Radio size={12} /> Internal Cluster Mesh & Health Heartbeats
        </div>
      </div>

      {/* Worker Nodes Grid */}
      <div className="topology-workers-grid">
        {workers.map(worker => {
          const status = worker.status || 'UNKNOWN';
          const isActive = status === 'ACTIVE';
          const isMaintenance = status === 'MAINTENANCE';
          const cpuPct = Math.min(Math.max(Math.round(worker.cpuUsagePercent ?? 0), 0), 100);
          const memPct = worker.totalMemoryMb
            ? Math.min(Math.max(Math.round(((worker.usedMemoryMb ?? 0) / worker.totalMemoryMb) * 100), 0), 100)
            : 0;

          const statusVariant = isActive ? 'green' : isMaintenance ? 'yellow' : 'red';

          return (
            <div
              key={worker.workerId}
              className={`topology-node-card status-${status.toLowerCase()}`}
              onClick={() => navigate(`/admin/workers/${worker.workerId}`)}
              role="button"
              tabIndex={0}
            >
              <div className="topology-node-header">
                <div className="topology-node-title-wrap">
                  <div className={`topology-node-icon ${status.toLowerCase()}`}>
                    <Server size={18} />
                  </div>
                  <div>
                    <div className="topology-node-name">{worker.workerName}</div>
                    <div className="topology-node-ip mono">{worker.ipAddress || 'unknown ip'}</div>
                  </div>
                </div>
                <Badge variant={statusVariant}>{status}</Badge>
              </div>

              {/* Resource Bars */}
              <div className="topology-node-bars">
                <div className="topology-bar-group">
                  <div className="topology-bar-label">
                    <span><Cpu size={12} /> CPU</span>
                    <strong>{cpuPct}% ({worker.totalCpuCores} cores)</strong>
                  </div>
                  <div className="topology-progress-track">
                    <div
                      className={`topology-progress-fill ${cpuPct > 85 ? 'danger' : ''}`}
                      style={{ width: `${cpuPct}%` }}
                    />
                  </div>
                </div>

                <div className="topology-bar-group">
                  <div className="topology-bar-label">
                    <span><MemoryStick size={12} /> Memory</span>
                    <strong>{memPct}% ({worker.usedMemoryMb || 0}/{worker.totalMemoryMb || 0} MB)</strong>
                  </div>
                  <div className="topology-progress-track">
                    <div
                      className={`topology-progress-fill ${memPct > 85 ? 'danger' : ''}`}
                      style={{ width: `${memPct}%` }}
                    />
                  </div>
                </div>
              </div>

              {/* Footer info */}
              <div className="topology-node-footer">
                <div className="topology-node-metric">
                  <Box size={13} />
                  <span><strong>{worker.runningContainers ?? 0}</strong> containers</span>
                </div>
                <div className="topology-node-metric">
                  <Star size={13} color="var(--accent-yellow)" />
                  <span>Score <strong>{(worker.score ?? 0).toFixed(2)}</strong></span>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
