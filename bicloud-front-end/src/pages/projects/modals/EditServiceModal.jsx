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
  AlertTriangle,
  Box,
  Cpu,
  Globe,
  HardDrive,
  Layers,
  Network,
  Save,
  Server,
  ShieldCheck,
} from 'lucide-react';

const SERVICE_LIMITS = {
  port: { label: 'Container port', min: 1, max: 65535 },
  memory: { label: 'Memory', min: 64, max: 4096 },
  cpu: { label: 'CPU', min: 0.1, max: 4.0 },
};

export const EditServiceModal = ({ service, isOpen, onClose, onSuccess }) => {
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
  const [exposeExternally, setExposeExternally] = useState(service.exposeExternally ?? false);
  const [autoscaling, setAutoscaling] = useState(() => autoscalingFormFromService(service));
  const [envVars, setEnvVars] = useState(
    () => Object.entries(service.environmentVariables || {}).map(([key, value]) => ({ key, value }))
  );

  const handleChange = (e) => {
    const { name, value } = e.target;
    setForm(prev => ({ ...prev, [name]: value }));
    setError('');
  };

  const handleAutoscalingChange = (e) => {
    const { name, value } = e.target;
    setAutoscaling(prev => ({ ...prev, [name]: value }));
    setError('');
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    if (!form.imageName.trim()) return setError('Docker image name is required.');

    const containerPort = parseIntegerInRange(form.containerPort, SERVICE_LIMITS.port);
    if (containerPort.error) return setError(containerPort.error);

    const memoryLimitMb = parseIntegerInRange(form.memoryLimitMb, SERVICE_LIMITS.memory);
    if (memoryLimitMb.error) return setError(memoryLimitMb.error);

    const cpuLimit = parseDecimalInRange(form.cpuLimit, SERVICE_LIMITS.cpu);
    if (cpuLimit.error) return setError(cpuLimit.error);

    const autoscalingPolicy = parseAutoscalingPolicy(autoscaling);
    if (autoscalingPolicy.error) return setError(autoscalingPolicy.error);

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
      await projectService.updateImage(service.id, {
        imageName: form.imageName.trim(),
        containerPort: containerPort.value,
        memoryLimitMb: memoryLimitMb.value,
        cpuLimit: cpuLimit.value,
        ...autoscalingPolicy.value,
        environmentVariables: envMap,
        allowInternet: isAdmin ? allowInternet : (service.allowInternet ?? false),
        exposeExternally,
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

  const hasRuntimeImpact = service.runningReplicas > 0 || service.desiredReplicas > 0;

  return (
    <Modal isOpen onClose={onClose} title={`Edit service / ${service.serviceName}`} maxWidth={1080}>
      <form onSubmit={handleSubmit} className="cloud-form">
        {error && <div className="alert alert-error">{error}</div>}

        <div className="cloud-form-layout">
          <div className="cloud-form-main">
            {hasRuntimeImpact && (
              <div className="alert alert-info">
                <AlertTriangle size={16} />
                Running containers will be recreated after saving this configuration.
              </div>
            )}

            <FormSection icon={Layers} title="Image and runtime" subtitle="Service name and replica count are managed separately.">
              <Input
                label="Container image"
                name="imageName"
                value={form.imageName}
                onChange={handleChange}
                placeholder="nginx:latest"
                icon={Box}
              />
              <div className="field-grid three">
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

            <FormSection icon={Activity} title="Autoscaling" subtitle="Policy changes do not recreate running containers.">
              <AutoscalingPolicyFields
                form={autoscaling}
                onToggle={(enabled) => setAutoscaling(prev => ({ ...prev, autoscalingEnabled: enabled }))}
                onChange={handleAutoscalingChange}
              />
            </FormSection>

            <FormSection icon={ShieldCheck} title="Network policy" subtitle="Ingress can change without modifying the service name.">
              <div className="policy-options">
                <PolicyOption
                  active={exposeExternally}
                  checked={exposeExternally}
                  onChange={setExposeExternally}
                  icon={Network}
                  title="Expose through gateway"
                  description="Enables host-based north-south access while preserving internal mesh routing."
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

            <FormSection icon={ShieldCheck} title="Environment variables" subtitle="Stored values are applied when containers are recreated.">
              <EnvVarsEditor ref={envEditorRef} envVars={envVars} setEnvVars={setEnvVars} />
            </FormSection>
          </div>

          <ReviewPanel
            serviceName={service.serviceName}
            imageName={form.imageName || 'Not set'}
            replicas={`${service.runningReplicas ?? 0}/${service.desiredReplicas ?? 0}`}
            port={form.containerPort}
            memory={form.memoryLimitMb}
            cpu={form.cpuLimit}
            envCount={envVars.filter(e => e.key.trim()).length}
            autoscaling={autoscaling.autoscalingEnabled
              ? `${autoscaling.minReplicas}-${autoscaling.maxReplicas} @ ${autoscaling.targetCpuPercent}%`
              : 'Manual'}
            external={exposeExternally ? 'Gateway exposed' : 'Mesh only'}
            egress={isAdmin ? (allowInternet ? 'Allowed' : 'Isolated') : (service.allowInternet ? 'Allowed' : 'Isolated')}
            impact={hasRuntimeImpact ? 'Recreate containers' : 'No running replicas'}
          />
        </div>

        <div className="modal-actions">
          <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={loading} icon={Save}>Save changes</Button>
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

const ReviewPanel = ({ serviceName, imageName, replicas, port, memory, cpu, envCount, autoscaling, external, egress, impact }) => (
  <aside className="form-review">
    <div className="form-review-header">
      <div className="form-review-title">
        <Server size={15} />
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
      <ReviewRow label="External" value={external} />
      <ReviewRow label="Egress" value={egress} />
      <ReviewRow label="Impact" value={impact} />
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
