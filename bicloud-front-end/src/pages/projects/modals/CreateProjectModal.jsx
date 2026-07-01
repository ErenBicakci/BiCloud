import React, { useState } from 'react';
import { projectService } from '../../../services/project.service';
import { useToast } from '../../../context/ToastContext';
import { extractError } from '../../../utils/common';
import { Modal } from '../../../components/ui/Modal';
import { Button, Input } from '../../../components/ui';

export const CreateProjectModal = ({ isOpen, onClose, onSuccess }) => {
  const { success } = useToast();
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    const val = name.trim();
    if (!val) return setError('Project name cannot be empty.');
    if (val.length < 2 || val.length > 50) return setError('Project name must be between 2 and 50 characters.');
    if (!/^[a-z][a-z0-9-]+$/.test(val)) return setError('Only lowercase letters, numbers, and hyphens (-) are allowed; must start with a lowercase letter.');
    if (val.endsWith('-')) return setError('Project name cannot end with a hyphen (-)');
    if (val.startsWith('bicloud-')) return setError('The "bicloud-" prefix is reserved by the system and cannot be used.');

    setLoading(true);
    try {
      const { data } = await projectService.create({ name: name.trim() });
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
    <Modal isOpen={isOpen} onClose={onClose} title="Create New Project">
      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
        {error && <div className="alert alert-error">{error}</div>}
        <Input
          label="Project Name"
          placeholder="my-project"
          value={name}
          onChange={(e) => { setName(e.target.value.toLowerCase()); setError(''); }}
          hint="2–50 chars; lowercase letters (a-z), numbers, and hyphens (-) only. 'bicloud-' prefix is not allowed."
        />
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={loading}>Create</Button>
        </div>
      </form>
    </Modal>
  );
};
