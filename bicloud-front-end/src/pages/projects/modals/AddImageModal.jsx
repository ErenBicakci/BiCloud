import React, { useState, useRef } from 'react';
import { projectService } from '../../../services/project.service';
import { useToast } from '../../../context/ToastContext';
import { useAuth } from '../../../context/AuthContext';
import { extractError } from '../../../utils/common';
import { Modal } from '../../../components/ui/Modal';
import { Button, Input, Badge } from '../../../components/ui';
import { EnvVarsEditor } from './EnvVarsEditor';
import { AutoscalingPolicyFields } from './AutoscalingPolicyFields';
import { autoscalingFormFromService, parseAutoscalingPolicy } from './autoscalingPolicy';
import {
  Activity,
  Box,
  Cpu,
  Globe,
  HardDrive,
  Layers,
  Network,
  Plus,
  Server,
  ShieldCheck,
  Tag,
} from 'lucide-react';

const SERVICE_LIMITS = {
  replicas: { label: 'Replicas', min: 1, max: 10 },
  port: { label: 'Container port', min: 1, max: 65535 },
  memory: { label: 'Memory', min: 64, max: 4096 },
  cpu: { label: 'CPU', min: 0.1, max: 4.0 },
};

export const AddImageModal = ({ projectId, projectName, isOpen, onClose, onSuccess }) => {
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
  const [exposeExternally, setExposeExternally] = useState(false);
  const [autoscaling, setAutoscaling] = useState(() => autoscalingFormFromService());

  const handleChange = (e) => {
    const { name, value } = e.target;
    const normalized = name === 'serviceName' ? value.toLowerCase() : value;
    setForm(prev => ({ ...prev, [name]: normalized }));
    setError('');
  };

  const handleAutoscalingChange = (e) => {
    const { name, value } = e.target;
    setAutoscaling(prev => ({ ...prev, [name]: value }));
    setError('');
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    const svc = form.serviceName.trim();
    if (!svc) return setError('Service name cannot be empty.');
    if (svc.length < 2 || svc.length > 50) return setError('Service name must be between 2 and 50 characters.');
    if (!/^[a-z][a-z0-9-]+$/.test(svc)) return setError('Service name can only contain lowercase letters, numbers, and hyphens (-); must start with a lowercase letter.');
    if (svc.endsWith('-')) return setError('Service name cannot end with a hyphen (-).');
    if (svc.startsWith('bicloud-')) return setError('The "bicloud-" prefix is reserved by the system and cannot be used.');
    if (!form.imageName.trim()) return setError('Docker image name is required.');

    const desiredReplicas = parseIntegerInRange(form.desiredReplicas, SERVICE_LIMITS.replicas);
    if (desiredReplicas.error) return setError(desiredReplicas.error);

    const autoscalingPolicy = parseAutoscalingPolicy(autoscaling, desiredReplicas.value);
    if (autoscalingPolicy.error) return setError(autoscalingPolicy.error);

    const containerPort = parseIntegerInRange(form.containerPort, SERVICE_LIMITS.port);
    if (containerPort.error) return setError(containerPort.error);

    const memoryLimitMb = parseIntegerInRange(form.memoryLimitMb, SERVICE_LIMITS.memory);
    if (memoryLimitMb.error) return setError(memoryLimitMb.error);

    const cpuLimit = parseDecimalInRange(form.cpuLimit, SERVICE_LIMITS.cpu);
    if (cpuLimit.error) return setError(cpuLimit.error);

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
        projectId: parseInt(projectId, 10),
        ...form,
        serviceName: svc,
        imageName: form.imageName.trim(),
        desiredReplicas: desiredReplicas.value,
        ...autoscalingPolicy.value,
        containerPort: containerPort.value,
        memoryLimitMb: memoryLimitMb.value,
        cpuLimit: cpuLimit.value,
        environmentVariables: envMap,
        allowInternet: isAdmin ? allowInternet : false,
        exposeExternally,
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

  const externalHost = exposeExternally && form.serviceName && projectName
    ? `${form.serviceName}.${projectName}.bicloud.local:9000`
    : 'Disabled';

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Add service" maxWidth={1080}>
      <form onSubmit={handleSubmit} className="cloud-form">
        {error && <div className="alert alert-error">{error}</div>}

        <div className="cloud-form-layout">
          <div className="cloud-form-main">
            <FormSection icon={Layers} title="Service identity" subtitle="Service name becomes the mesh route key.">
              <div className="field-grid">
                <Input
                  label="Service name"
                  name="serviceName"
                  value={form.serviceName}
                  onChange={handleChange}
                  placeholder="web-api"
                  icon={Tag}
                />
                <Input
                  label="Container image"
                  name="imageName"
                  value={form.imageName}
                  onChange={handleChange}
                  placeholder="nginx:latest"
                  icon={Box}
                />
              </div>
            </FormSection>

            <FormSection icon={Server} title="Runtime sizing" subtitle="Scheduler uses these values for placement and capacity checks.">
              <div className="field-grid three">
                <Input
                  label="Replicas"
                  name="desiredReplicas"
                  type="number"
                  min={SERVICE_LIMITS.replicas.min}
                  max={SERVICE_LIMITS.replicas.max}
                  value={form.desiredReplicas}
                  onChange={handleChange}
                  icon={Layers}
                />
                <Input
                  label="Container port"
                  name="containerPort"
                  type="number"
                  min={SERVICE_LIMITS.port.min}
                  max={SERVICE_LIMITS.port.max}
                  value={form.containerPort}
                  onChange={handleChange}
                  icon={Network}
                />
                <Input
                  label="Memory (MB)"
                  name="memoryLimitMb"
                  type="number"
                  min={SERVICE_LIMITS.memory.min}
                  max={SERVICE_LIMITS.memory.max}
                  value={form.memoryLimitMb}
                  onChange={handleChange}
                  icon={HardDrive}
                />
                <Input
                  label="CPU (core)"
                  name="cpuLimit"
                  type="number"
                  min={SERVICE_LIMITS.cpu.min}
                  max={SERVICE_LIMITS.cpu.max}
                  step="0.1"
                  value={form.cpuLimit}
                  onChange={handleChange}
                  icon={Cpu}
                />
              </div>
            </FormSection>

            <FormSection icon={Activity} title="Autoscaling" subtitle="CPU policy updates the desired replica count.">
              <AutoscalingPolicyFields
                form={autoscaling}
                onToggle={(enabled) => setAutoscaling(prev => ({ ...prev, autoscalingEnabled: enabled }))}
                onChange={handleAutoscalingChange}
              />
            </FormSection>

            <FormSection icon={ShieldCheck} title="Network policy" subtitle="Ingress and egress are separate controls.">
              <div className="policy-options">
                <PolicyOption
                  active={exposeExternally}
                  checked={exposeExternally}
                  onChange={setExposeExternally}
                  icon={Network}
                  title="Expose through gateway"
                  description="Enables host-based north-south access. Mesh access remains available either way."
                  badge={exposeExternally ? 'Gateway' : 'Mesh only'}
                />

                {isAdmin && (
                  <PolicyOption
                    active={allowInternet}
                    checked={allowInternet}
                    onChange={setAllowInternet}
                    icon={Globe}
                    title="Allow internet egress"
                    description="Grants outbound internet access from containers. Keep disabled for isolated workloads."
                    badge={allowInternet ? 'Admin enabled' : 'Isolated'}
                  />
                )}
              </div>
            </FormSection>

            <FormSection icon={ShieldCheck} title="Environment variables" subtitle="BICLOUD_* keys are reserved by the platform.">
              <EnvVarsEditor ref={envEditorRef} envVars={envVars} setEnvVars={setEnvVars} />
            </FormSection>
          </div>

          <ReviewPanel
            serviceName={form.serviceName || 'Not set'}
            imageName={form.imageName || 'Not set'}
            replicas={form.desiredReplicas}
            port={form.containerPort}
            memory={form.memoryLimitMb}
            cpu={form.cpuLimit}
            envCount={envVars.filter(e => e.key.trim()).length}
            externalHost={externalHost}
            egress={isAdmin ? (allowInternet ? 'Allowed' : 'Isolated') : 'Isolated'}
            autoscaling={autoscaling.autoscalingEnabled
              ? `${autoscaling.minReplicas}-${autoscaling.maxReplicas} @ ${autoscaling.targetCpuPercent}%`
              : 'Manual'}
          />
        </div>

        <div className="modal-actions">
          <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={loading} icon={Plus}>Add service</Button>
        </div>
      </form>
    </Modal>
  );
};

const FormSection = ({ icon: Icon, title, subtitle, children }) => (
  <section className="form-section">
    <div className="form-section-header">
      <div className="icon-box">
        {React.createElement(Icon, { size: 16 })}
      </div>
      <div>
        <div className="form-section-title">{title}</div>
        {subtitle && <div className="form-section-subtitle">{subtitle}</div>}
      </div>
    </div>
    <div className="form-section-body">{children}</div>
  </section>
);

const PolicyOption = ({ active, checked, onChange, icon: Icon, title, description, badge }) => (
  <label className={`policy-option ${active ? 'active' : ''}`}>
    <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} />
    <div>
      <div className="policy-option-title">
        {React.createElement(Icon, { size: 15 })}
        {title}
      </div>
      <div className="policy-option-desc">{description}</div>
    </div>
    <Badge variant={active ? 'blue' : 'gray'}>{badge}</Badge>
  </label>
);

const ReviewPanel = ({ serviceName, imageName, replicas, port, memory, cpu, envCount, externalHost, egress, autoscaling }) => (
  <aside className="form-review">
    <div className="form-review-header">
      <div className="form-review-title">
        <ShieldCheck size={15} />
        Review
      </div>
    </div>
    <div className="form-review-body">
      <ReviewRow label="Service" value={serviceName} />
      <ReviewRow label="Image" value={imageName} />
      <ReviewRow label="Replicas" value={replicas} />
      <ReviewRow label="Port" value={port} />
      <ReviewRow label="Memory" value={`${memory || '-'} MB`} />
      <ReviewRow label="CPU" value={`${cpu || '-'} core`} />
      <ReviewRow label="Env vars" value={envCount} />
      <ReviewRow label="Autoscale" value={autoscaling} />
      <ReviewRow label="External" value={externalHost} />
      <ReviewRow label="Egress" value={egress} />
    </div>
  </aside>
);

const ReviewRow = ({ label, value }) => (
  <div className="review-row">
    <div className="review-label">{label}</div>
    <div className="review-value" title={String(value)}>{value}</div>
  </div>
);

const parseIntegerInRange = (raw, limit) => {
  const text = String(raw ?? '').trim();
  const value = Number(text);

  if (!text) return { error: `${limit.label} is required.` };
  if (!Number.isInteger(value)) return { error: `${limit.label} must be a whole number.` };
  if (value < limit.min || value > limit.max) {
    return { error: `${limit.label} must be between ${limit.min} and ${limit.max}.` };
  }
  return { value };
};

const parseDecimalInRange = (raw, limit) => {
  const text = String(raw ?? '').trim();
  const value = Number(text);

  if (!text) return { error: `${limit.label} is required.` };
  if (!Number.isFinite(value)) return { error: `${limit.label} must be a number.` };
  if (value < limit.min || value > limit.max) {
    return { error: `${limit.label} must be between ${limit.min} and ${limit.max}.` };
  }
  return { value };
};
