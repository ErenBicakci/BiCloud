import React, { useState, useRef } from 'react';
import { projectService } from '../../../services/project.service';
import { useToast } from '../../../context/ToastContext';
import { useAuth } from '../../../context/AuthContext';
import { extractError } from '../../../utils/common';
import { Modal } from '../../../components/ui/Modal';
import { Button, Input } from '../../../components/ui';
import { EnvVarsEditor } from './EnvVarsEditor';
import { AlertTriangle, Globe } from 'lucide-react';

/**
 * Edits the configuration of an existing service.
 * Service name cannot be changed (mesh/gateway routes depend on the name);
 * replica count is managed via the Scale modal.
 * Saving stops and recreates running containers with the new configuration.
 */
export const EditServiceModal = ({ service, isOpen, onClose, onSuccess }) => {
  // Do not mount while closed: the form is initialized with the service's current values on every open
  if (!isOpen || !service) return null;
  return <EditServiceForm service={service} onClose={onClose} onSuccess={onSuccess} />;
};

const EditServiceForm = ({ service, onClose, onSuccess }) => {
  const { success } = useToast();
  const { isAdmin } = useAuth();
  const envEditorRef = useRef(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [form, setForm] = useState({
    imageName: service.imageName ?? '',
    containerPort: String(service.containerPort ?? 80),
    memoryLimitMb: String(service.memoryLimitMb ?? 256),
    cpuLimit: String(service.cpuLimit ?? 0.5),
  });
  const [allowInternet, setAllowInternet] = useState(service.allowInternet ?? false);
  const [envVars, setEnvVars] = useState(
    () => Object.entries(service.environmentVariables || {}).map(([key, value]) => ({ key, value }))
  );

  const handleChange = (e) => {
    const { name, value } = e.target;
    setForm(prev => ({ ...prev, [name]: value }));
    setError('');
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    if (!form.imageName.trim()) return setError('Docker image name is required.');

    const entries = envEditorRef.current?.commit();
    if (entries === null) return; // bulk mode parse error — shown in editor

    const dupKey = entries.map(ev => ev.key.trim()).find((k, i, arr) => k && arr.indexOf(k) !== i);
    if (dupKey) return setError(`Key "${dupKey}" has been entered more than once.`);

    const envMap = {};
    for (const { key, value } of entries) {
      if (key.trim()) envMap[key.trim()] = value;
    }

    setLoading(true);
    try {
      await projectService.updateImage(service.id, {
        imageName: form.imageName.trim(),
        containerPort: parseInt(form.containerPort),
        memoryLimitMb: parseInt(form.memoryLimitMb),
        cpuLimit: parseFloat(form.cpuLimit),
        environmentVariables: envMap,
        // non-admins must echo the current value: changing it is admin-only server-side
        allowInternet: isAdmin ? allowInternet : (service.allowInternet ?? false),
      });
      success('Service updated. Containers are being recreated with the new configuration.');
      onSuccess();
      onClose();
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal isOpen onClose={onClose} title={`Edit Service: ${service.serviceName}`} maxWidth={560}>
      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {error && <div className="alert alert-error">{error}</div>}

        {(service.runningReplicas > 0 || service.desiredReplicas > 0) && (
          <div style={{
            display: 'flex', gap: 10, alignItems: 'flex-start',
            padding: '10px 12px', borderRadius: 'var(--radius-sm)',
            background: 'rgba(210,153,34,.08)', border: '1px solid rgba(210,153,34,.3)',
            fontSize: '0.78rem', color: 'var(--text-secondary)'
          }}>
            <AlertTriangle size={15} color="var(--accent-yellow)" style={{ flexShrink: 0, marginTop: 1 }} />
            When you save, running containers will be stopped and recreated with the new configuration. A brief downtime may occur.
          </div>
        )}

        <div className="form-group">
          <label className="form-label">Docker Image</label>
          <input
            className="input"
            name="imageName"
            value={form.imageName}
            onChange={handleChange}
            placeholder="nginx:latest, redis:7-alpine, myrepo/myapp:v1.0 ..."
          />
          <span className="form-hint">Change the tag to switch to a new version.</span>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Input
            label="Port" name="containerPort" type="number" value={form.containerPort} onChange={handleChange}
          />
          <Input
            label="Memory (MB)" name="memoryLimitMb" type="number" value={form.memoryLimitMb} onChange={handleChange}
          />
          <Input
            label="CPU (Core)" name="cpuLimit" type="number" step="0.1" value={form.cpuLimit} onChange={handleChange}
          />
        </div>

        <EnvVarsEditor ref={envEditorRef} envVars={envVars} setEnvVars={setEnvVars} />

        {isAdmin && (
          <label style={{
            display: 'flex', gap: 10, alignItems: 'flex-start', cursor: 'pointer',
            padding: '10px 12px', borderRadius: 'var(--radius-sm)',
            background: 'rgba(88,166,255,.06)', border: '1px solid rgba(88,166,255,.25)',
            fontSize: '0.8rem', color: 'var(--text-secondary)',
          }}>
            <input
              type="checkbox"
              checked={allowInternet}
              onChange={e => setAllowInternet(e.target.checked)}
              style={{ marginTop: 2 }}
            />
            <span>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontWeight: 600, color: 'var(--text-primary)' }}>
                <Globe size={13} /> Allow internet access (admin)
              </span>
              <br />
              Containers normally run on an isolated network with no outbound access.
              Enable only if this service must reach external APIs.
            </span>
          </label>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 8 }}>
          <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={loading}>Save</Button>
        </div>
      </form>
    </Modal>
  );
};
