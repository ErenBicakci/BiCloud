import React, { useState, forwardRef, useImperativeHandle } from 'react';
import { Button } from '../../../components/ui';
import { Check, ClipboardList, Copy, Download, List, PenLine, Plus, Trash2 } from 'lucide-react';

export const EnvVarsEditor = forwardRef(function EnvVarsEditor({ envVars, setEnvVars }, ref) {
  const [envMode, setEnvMode] = useState('single');
  const [bulkText, setBulkText] = useState('');
  const [bulkError, setBulkError] = useState('');
  const [copied, setCopied] = useState(false);

  const addEnvVar = () => setEnvVars(prev => [...prev, { key: '', value: '' }]);
  const removeEnvVar = (index) => setEnvVars(prev => prev.filter((_, i) => i !== index));
  const handleEnvChange = (index, field, val) => {
    setEnvVars(prev => prev.map((item, i) => i === index ? { ...item, [field]: val } : item));
  };

  const parseBulkText = (text) => {
    const lines = text.split('\n');
    const parsed = [];
    const seen = new Set();

    for (let i = 0; i < lines.length; i++) {
      const raw = lines[i];
      const trimmed = raw.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;

      const eqIdx = trimmed.indexOf('=');
      if (eqIdx === -1) {
        throw new Error(`Line ${i + 1}: missing "=" delimiter in "${raw}"`);
      }

      let key = trimmed.slice(0, eqIdx).trim();
      let value = trimmed.slice(eqIdx + 1).trim();

      // Strip matching wrapping quotes
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }

      if (!key) {
        throw new Error(`Line ${i + 1}: variable key cannot be empty.`);
      }
      if (key.startsWith('BICLOUD_')) {
        throw new Error(`Line ${i + 1}: the "BICLOUD_" prefix is reserved by the platform.`);
      }
      if (seen.has(key)) {
        throw new Error(`Duplicate key "${key}" found at line ${i + 1}.`);
      }
      seen.add(key);
      parsed.push({ key, value });
    }
    return parsed;
  };

  const syncBulkToRows = () => {
    setBulkError('');
    try {
      const parsed = parseBulkText(bulkText);
      setEnvVars(parsed);
      setEnvMode('single');
      setBulkText('');
      return parsed;
    } catch (err) {
      setBulkError(err.message);
      return null;
    }
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

  const copyAsEnv = () => {
    let text;
    if (envMode === 'bulk') {
      text = bulkText;
    } else {
      text = envVars
        .filter(e => e.key.trim())
        .map(e => `${e.key}=${e.value}`)
        .join('\n');
    }
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
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
              title="Form row editor"
            >
              <PenLine size={12} /> Rows
            </button>
            <button
              type="button"
              className={envMode === 'bulk' ? 'active' : ''}
              onClick={envMode === 'single' ? switchToBulk : undefined}
              title="Bulk raw text editor (.env)"
            >
              <List size={12} /> .env (Bulk)
            </button>
          </div>

          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={copyAsEnv}
            title="Copy all variables as .env text"
          >
            {copied ? <Check size={13} color="var(--accent-green)" /> : <Copy size={13} />}
            {copied ? 'Copied' : 'Copy .env'}
          </button>

          {envMode === 'single' && (
            <Button variant="ghost" size="sm" type="button" icon={Plus} onClick={addEnvVar}>
              Add Row
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
            placeholder={'# Paste .env variables below:\nDATABASE_URL=postgres://user:pass@host:5432/db\nAPI_KEY=secret_key_123\nPORT=8080'}
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
              No environment variables defined yet. Click "Add Row" or switch to ".env (Bulk)".
            </div>
          )}

          {envVars.length > 0 && (
            <div style={{ display: 'grid', gap: 8 }}>
              {envVars.map((ev, i) => (
                <div key={i} className="env-row">
                  <input
                    className="input mono"
                    placeholder="KEY (e.g. PORT)"
                    value={ev.key}
                    onChange={e => handleEnvChange(i, 'key', e.target.value)}
                  />
                  <input
                    className="input mono"
                    placeholder="value (e.g. 8080)"
                    value={ev.value}
                    onChange={e => handleEnvChange(i, 'value', e.target.value)}
                  />
                  <button
                    type="button"
                    onClick={() => removeEnvVar(i)}
                    className="btn-icon"
                    title="Remove variable"
                    aria-label="Remove variable"
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

