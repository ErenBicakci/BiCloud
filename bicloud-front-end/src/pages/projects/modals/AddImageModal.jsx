import React, { useState, useRef } from 'react';
import { projectService } from '../../../services/project.service';
import { useToast } from '../../../context/ToastContext';
import { useAuth } from '../../../context/AuthContext';
import { extractError } from '../../../utils/common';
import { Modal } from '../../../components/ui/Modal';
import { Button, Input } from '../../../components/ui';
import { EnvVarsEditor } from './EnvVarsEditor';
import { Globe } from 'lucide-react';

export const AddImageModal = ({ projectId, isOpen, onClose, onSuccess }) => {
  const { success } = useToast();
  const { isAdmin } = useAuth();
  const envEditorRef = useRef(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [form, setForm] = useState({
    serviceName: '',
    imageName: '',
    desiredReplicas: '1',
    containerPort: '80',
    memoryLimitMb: '256',
    cpuLimit: '0.5',
  });
  const [envVars, setEnvVars] = useState([]);
  const [allowInternet, setAllowInternet] = useState(false);

  const handleChange = (e) => {
    const { name, value } = e.target;
    const normalized = name === 'serviceName' ? value.toLowerCase() : value;
    setForm(prev => ({ ...prev, [name]: normalized }));
    setError('');
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    const svc = form.serviceName.trim();
    if (!svc) return setError('Service name cannot be empty.');
    if (svc.length < 2 || svc.length > 50) return setError('Service name must be between 2 and 50 characters.');
    if (!/^[a-z][a-z0-9-]+$/.test(svc)) return setError('Service name can only contain lowercase letters, numbers, and hyphens (-); must start with a lowercase letter.');
    if (svc.endsWith('-')) return setError('Service name cannot end with a hyphen (-)');
    if (svc.startsWith('bicloud-')) return setError('The "bicloud-" prefix is reserved by the system and cannot be used.');
    if (!form.imageName.trim()) return setError('Docker image name is required.');

    // If in bulk mode, parse the textarea; errors are shown in the editor
    const entries = envEditorRef.current?.commit();
    if (entries === null) return;

    const dupKey = entries.map(ev => ev.key.trim()).find((k, i, arr) => k && arr.indexOf(k) !== i);
    if (dupKey) return setError(`Key "${dupKey}" has been entered more than once.`);

    const envMap = {};
    for (const { key, value } of entries) {
      if (key.trim()) envMap[key.trim()] = value;
    }

    setLoading(true);
    try {
      await projectService.addImage({
        projectId: parseInt(projectId),
        ...form,
        desiredReplicas: parseInt(form.desiredReplicas),
        containerPort: parseInt(form.containerPort),
        memoryLimitMb: parseInt(form.memoryLimitMb),
        cpuLimit: parseFloat(form.cpuLimit),
        environmentVariables: envMap,
        allowInternet: isAdmin ? allowInternet : false,
      });
      success('Service added successfully.');
      onSuccess();
      onClose();
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Add Service" maxWidth={560}>
      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {error && <div className="alert alert-error">{error}</div>}

        {/* Docker Image */}
        <div className="form-group">
          <label className="form-label">Docker Image</label>
          <input
            className="input"
            name="imageName"
            value={form.imageName}
            onChange={handleChange}
            placeholder="nginx:latest, redis:7-alpine, myrepo/myapp:v1.0 ..."
          />
          <span className="form-hint">Image from Docker Hub or any accessible registry.</span>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Input
            label="Service Name" name="serviceName" value={form.serviceName} onChange={handleChange}
            placeholder="web-api"
            hint="2-50 chars; a-z, 0-9, hyphen. 'bicloud-' prefix not allowed."
          />
          <Input
            label="Port" name="containerPort" type="number" value={form.containerPort} onChange={handleChange}
          />
          <Input
            label="Replica" name="desiredReplicas" type="number" value={form.desiredReplicas} onChange={handleChange}
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
          <Button type="submit" loading={loading}>Add</Button>
        </div>
      </form>
    </Modal>
  );
};
