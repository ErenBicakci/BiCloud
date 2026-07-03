import React, { useState } from 'react';
import { projectService } from '../../../services/project.service';
import { useToast } from '../../../context/ToastContext';
import { extractError } from '../../../utils/common';
import { Modal } from '../../../components/ui/Modal';
import { Button, Input } from '../../../components/ui';
import { CheckCircle2, FolderKanban, ShieldCheck, Tag } from 'lucide-react';

export const CreateProjectModal = ({ isOpen, onClose, onSuccess }) => {
  const { success } = useToast();
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const normalizedName = name.trim();

  const handleSubmit = async (e) => {
    e.preventDefault();
    const val = normalizedName;
    if (!val) return setError('Project name cannot be empty.');
    if (val.length < 2 || val.length > 50) return setError('Project name must be between 2 and 50 characters.');
    if (!/^[a-z][a-z0-9-]+$/.test(val)) return setError('Only lowercase letters, numbers, and hyphens (-) are allowed; must start with a lowercase letter.');
    if (val.endsWith('-')) return setError('Project name cannot end with a hyphen (-).');
    if (val.startsWith('bicloud-')) return setError('The "bicloud-" prefix is reserved by the system and cannot be used.');
    if (val.startsWith('egress-')) return setError('The "egress-" prefix is reserved by the system and cannot be used.');

    setLoading(true);
    try {
      const { data } = await projectService.create({ name: val });
      success(`Project "${data.name}" created.`);
      onSuccess(data);
      onClose();
      setName('');
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create project" maxWidth={860}>
      <form onSubmit={handleSubmit} className="cloud-form">
        {error && <div className="alert alert-error">{error}</div>}

        <div className="cloud-form-layout">
          <div className="cloud-form-main">
            <FormSection
              icon={FolderKanban}
              title="Project identity"
              subtitle="This name is used by Docker networks, mesh routes, and gateway hostnames."
            >
              <Input
                label="Project name"
                placeholder="payments-api"
                value={name}
                onChange={(e) => {
                  setName(e.target.value.toLowerCase());
                  setError('');
                }}
                icon={Tag}
              />
            </FormSection>

            <FormSection icon={ShieldCheck} title="Naming constraints" subtitle="Reserved prefixes are blocked by the backend.">
              <div className="policy-options">
                <RuleRow text="2-50 characters" active={normalizedName.length >= 2 && normalizedName.length <= 50} />
                <RuleRow text="Lowercase letters, numbers, and hyphens" active={!normalizedName || /^[a-z][a-z0-9-]+$/.test(normalizedName)} />
                <RuleRow text="Cannot end with a hyphen" active={!normalizedName.endsWith('-')} />
                <RuleRow text="Cannot start with bicloud- or egress-" active={!normalizedName.startsWith('bicloud-') && !normalizedName.startsWith('egress-')} />
              </div>
            </FormSection>
          </div>

          <ReviewPanel
            name={normalizedName || 'Not set'}
            network={normalizedName ? `${normalizedName} network` : 'Created after submit'}
            mesh={normalizedName ? `/_bicloud/mesh/${normalizedName}` : 'Created after submit'}
          />
        </div>

        <div className="modal-actions">
          <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={loading} icon={FolderKanban}>Create project</Button>
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

const RuleRow = ({ text, active }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: active ? 'var(--accent-green)' : 'var(--text-muted)', fontSize: '.82rem' }}>
    <CheckCircle2 size={14} />
    <span>{text}</span>
  </div>
);

const ReviewPanel = ({ name, network, mesh }) => (
  <aside className="form-review">
    <div className="form-review-header">
      <div className="form-review-title">
        <ShieldCheck size={15} />
        Deployment surface
      </div>
    </div>
    <div className="form-review-body">
      <ReviewRow label="Name" value={name} />
      <ReviewRow label="Network" value={network} />
      <ReviewRow label="Mesh path" value={mesh} />
      <ReviewRow label="Scope" value="Private by default" />
    </div>
  </aside>
);

const ReviewRow = ({ label, value }) => (
  <div className="review-row">
    <div className="review-label">{label}</div>
    <div className="review-value" title={value}>{value}</div>
  </div>
);
