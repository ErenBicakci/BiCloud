import React, { useState, forwardRef, useImperativeHandle } from 'react';
import { Button } from '../../../components/ui';
import { ClipboardList, List, PenLine, Plus, Trash2 } from 'lucide-react';

export const EnvVarsEditor = forwardRef(function EnvVarsEditor({ envVars, setEnvVars }, ref) {
  const [envMode, setEnvMode] = useState('single');
  const [bulkText, setBulkText] = useState('');
  const [bulkError, setBulkError] = useState('');

  const addEnvVar = () => setEnvVars(prev => [...prev, { key: '', value: '' }]);
  const removeEnvVar = (index) => setEnvVars(prev => prev.filter((_, i) => i !== index));
  const handleEnvChange = (index, field, val) => {
    setEnvVars(prev => prev.map((item, i) => i === index ? { ...item, [field]: val } : item));
  };

  const syncBulkToRows = () => {
    setBulkError('');
    const lines = bulkText.split('\n');
    const parsed = [];
    const seen = new Set();

    for (let i = 0; i < lines.length; i++) {
      const raw = lines[i];
      const trimmed = raw.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;

      const eqIdx = trimmed.indexOf('=');
      if (eqIdx === -1) {
        setBulkError(`Line ${i + 1}: missing "=" in "${raw}"`);
        return null;
      }

      const key = trimmed.slice(0, eqIdx).trim();
      const value = trimmed.slice(eqIdx + 1);

      if (!key) {
        setBulkError(`Line ${i + 1}: key cannot be empty.`);
        return null;
      }
      if (key.startsWith('BICLOUD_')) {
        setBulkError(`Line ${i + 1}: the "BICLOUD_" prefix is reserved by the system.`);
        return null;
      }
      if (seen.has(key)) {
        setBulkError(`Key "${key}" has been entered more than once.`);
        return null;
      }
      seen.add(key);
      parsed.push({ key, value });
    }

    setEnvVars(parsed);
    setEnvMode('single');
    setBulkText('');
    return parsed;
  };

  useImperativeHandle(ref, () => ({
    commit: () => (envMode === 'bulk' ? syncBulkToRows() : envVars),
  }));

  const switchToBulk = () => {
    const text = envVars
      .filter(e => e.key.trim())
      .map(e => `${e.key}=${e.value}`)
      .join('\n');
    setBulkText(text);
    setBulkError('');
    setEnvMode('bulk');
  };

  const filledCount = envVars.filter(e => e.key.trim()).length;

  return (
    <div className="env-editor">
      <div className="env-editor-head">
        <div className="form-review-title" style={{ fontSize: '.84rem' }}>
          <ClipboardList size={15} />
          Variables
          {filledCount > 0 && <span className="badge badge-blue">{filledCount}</span>}
        </div>

        <div className="env-toolbar">
          <div className="segmented">
            <button
              type="button"
              className={envMode === 'single' ? 'active' : ''}
              onClick={() => envMode === 'bulk' && syncBulkToRows()}
            >
              <PenLine size={12} /> Rows
            </button>
            <button
              type="button"
              className={envMode === 'bulk' ? 'active' : ''}
              onClick={envMode === 'single' ? switchToBulk : undefined}
            >
              <List size={12} /> Bulk
            </button>
          </div>
          {envMode === 'single' && (
            <Button variant="ghost" size="sm" type="button" icon={Plus} onClick={addEnvVar}>
              Add
            </Button>
          )}
        </div>
      </div>

      {envMode === 'bulk' && (
        <div>
          <textarea
            value={bulkText}
            onChange={e => {
              setBulkText(e.target.value);
              setBulkError('');
            }}
            placeholder={'KEY=value\nREDIS_URL=redis://cache:6379\n# blank lines and comments are ignored'}
            rows={8}
            className="input mono"
            style={{
              minHeight: 180,
              resize: 'vertical',
              borderColor: bulkError ? 'var(--accent-red)' : undefined,
            }}
          />
          {bulkError && (
            <p style={{ fontSize: '.75rem', color: 'var(--accent-red)', marginTop: 6 }}>
              {bulkError}
            </p>
          )}
        </div>
      )}

      {envMode === 'single' && (
        <>
          {envVars.length === 0 && (
            <div className="env-empty">
              No environment variables
            </div>
          )}

          {envVars.length > 0 && (
            <div style={{ display: 'grid', gap: 8 }}>
              {envVars.map((ev, i) => (
                <div key={i} className="env-row">
                  <input
                    className="input mono"
                    placeholder="KEY"
                    value={ev.key}
                    onChange={e => handleEnvChange(i, 'key', e.target.value)}
                  />
                  <input
                    className="input mono"
                    placeholder="value"
                    value={ev.value}
                    onChange={e => handleEnvChange(i, 'value', e.target.value)}
                  />
                  <button
                    type="button"
                    onClick={() => removeEnvVar(i)}
                    className="btn-icon"
                    title="Remove"
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
});
