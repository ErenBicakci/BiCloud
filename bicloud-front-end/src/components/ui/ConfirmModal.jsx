import React, { useState } from 'react';
import { Modal } from './Modal';
import { Button } from './index';
import { AlertTriangle } from 'lucide-react';

/**
 * Confirmation modal used instead of window.confirm.
 *
 * Props:
 *   isOpen        – whether the modal is open
 *   title         – modal title
 *   message       – description (string or JSX)
 *   confirmLabel  – confirm button label (default "Confirm")
 *   variant       – confirm button variant (default "danger")
 *   requireText   – if provided, the user must type this text exactly to confirm
 *                   (for irreversible operations like project deletion)
 *   onConfirm     – called when confirmed (can be async; loading is shown until done)
 *   onClose       – cancel / close handler
 */
export const ConfirmModal = (props) => {
  // Don't mount while closed: state resets fresh on every open
  if (!props.isOpen) return null;
  return <ConfirmDialog {...props} />;
};

const ConfirmDialog = ({
  title = 'Are you sure?',
  message,
  confirmLabel = 'Confirm',
  variant = 'danger',
  requireText,
  onConfirm,
  onClose,
}) => {
  const [typed, setTyped] = useState('');
  const [loading, setLoading] = useState(false);

  const blocked = requireText && typed !== requireText;

  const handleConfirm = async () => {
    if (blocked || loading) return;
    setLoading(true);
    try {
      await onConfirm();
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal isOpen onClose={onClose} title={title} maxWidth={440}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginBottom: 20 }}>
        <div style={{
          flexShrink: 0, width: 38, height: 38, borderRadius: '50%',
          background: 'rgba(248,81,73,.12)', display: 'flex',
          alignItems: 'center', justifyContent: 'center'
        }}>
          <AlertTriangle size={18} color="var(--accent-red)" />
        </div>
        <div style={{ fontSize: '0.88rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
          {message}
        </div>
      </div>

      {requireText && (
        <div className="form-group" style={{ marginBottom: 20 }}>
          <label className="form-label">
            Type <code className="mono" style={{ color: 'var(--accent-red)' }}>{requireText}</code> to confirm
          </label>
          <input
            className="input"
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            placeholder={requireText}
            autoFocus
          />
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
        <Button variant="ghost" onClick={onClose} disabled={loading}>Cancel</Button>
        <Button variant={variant} onClick={handleConfirm} loading={loading} disabled={blocked}>
          {confirmLabel}
        </Button>
      </div>
    </Modal>
  );
};
