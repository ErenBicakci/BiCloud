import React, { useState, forwardRef, useImperativeHandle } from 'react';
import { Button } from '../../../components/ui';
import { List, PenLine } from 'lucide-react';

/**
 * Service env var editor: row-by-row and bulk (KEY=VALUE) paste modes.
 * Shared by AddImageModal and EditServiceModal.
 *
 * Props:
 *   envVars     – [{ key, value }] array
 *   setEnvVars  – state setter
 *
 * Ref API:
 *   commit() – returns the current env list; if in bulk mode, first parses the textarea.
 *              Returns null if there is a parse error (error is shown in the editor).
 *              Must be called before submit.
 */
export const EnvVarsEditor = forwardRef(function EnvVarsEditor({ envVars, setEnvVars }, ref) {
  const [envMode, setEnvMode] = useState('single');
  const [bulkText, setBulkText] = useState('');
  const [bulkError, setBulkError] = useState('');

  const addEnvVar = () => setEnvVars(prev => [...prev, { key: '', value: '' }]);
  const removeEnvVar = (index) => setEnvVars(prev => prev.filter((_, i) => i !== index));
  const handleEnvChange = (index, field, val) => {
    setEnvVars(prev => prev.map((item, i) => i === index ? { ...item, [field]: val } : item));
  };

  // Bulk mode: parses KEY=VALUE lines and converts to row mode.
  // Returns the parsed array on success, or null on error.
  const syncBulkToRows = () => {
    setBulkError('');
    const lines = bulkText.split('\n');
    const parsed = [];
    const seen = new Set();

    for (let i = 0; i < lines.length; i++) {
      const raw = lines[i];
      const trimmed = raw.trim();
      if (!trimmed || trimmed.startsWith('#')) continue; // empty / comment line

      const eqIdx = trimmed.indexOf('=');
      if (eqIdx === -1) {
        setBulkError(`Line ${i + 1}: missing "=" → "${raw}"`);
        return null;
      }

      const key   = trimmed.slice(0, eqIdx).trim();
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

  // When switching from row mode to bulk mode, dump existing env vars into the textarea
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
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <span className="form-label" style={{ margin: 0 }}>
          Environment Variables
          {filledCount > 0 && (
            <span style={{
              marginLeft: 8, fontSize: '0.7rem', fontWeight: 600,
              background: 'rgba(56,139,253,.15)', color: 'var(--accent-blue)',
              padding: '1px 7px', borderRadius: 10,
            }}>
              {filledCount}
            </span>
          )}
        </span>

        <div style={{ display: 'flex', gap: 6 }}>
          <button
            type="button"
            onClick={envMode === 'single' ? switchToBulk : syncBulkToRows}
            title={envMode === 'single' ? 'Switch to bulk paste mode' : 'Switch to row-by-row mode'}
            style={{
              display: 'flex', alignItems: 'center', gap: 5,
              padding: '4px 10px', borderRadius: 'var(--radius-sm)',
              background: 'rgba(139,148,158,.1)', border: '1px solid var(--border-default)',
              color: 'var(--text-secondary)', cursor: 'pointer', fontSize: '0.75rem', fontWeight: 600,
            }}
          >
            {envMode === 'single'
              ? <><List size={12} /> Bulk Paste</>
              : <><PenLine size={12} /> Row by Row</>
            }
          </button>
          {envMode === 'single' && (
            <Button variant="ghost" size="sm" type="button" onClick={addEnvVar}>+ Add</Button>
          )}
        </div>
      </div>

      {envMode === 'bulk' && (
        <div>
          <textarea
            value={bulkText}
            onChange={e => { setBulkText(e.target.value); setBulkError(''); }}
            placeholder={
              'KEY=value\nKAFKA_BROKER_ID=1\nKAFKA_ZOOKEEPER_CONNECT=zookeeper:2181\n# comment lines and blank lines are ignored'
            }
            rows={8}
            style={{
              width: '100%', boxSizing: 'border-box',
              padding: '10px 12px', borderRadius: 'var(--radius-sm)',
              background: 'var(--bg-elevated)', border: `1px solid ${bulkError ? 'var(--accent-red)' : 'var(--border-default)'}`,
              color: 'var(--text-primary)', fontFamily: 'monospace', fontSize: '0.8rem',
              resize: 'vertical',
            }}
          />
          {bulkError && (
            <p style={{ fontSize: '0.75rem', color: 'var(--accent-red)', marginTop: 4 }}>
              ⚠ {bulkError}
            </p>
          )}
          <p style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 4 }}>
            One <code>KEY=VALUE</code> per line. Blank lines and lines starting with <code>#</code> are ignored.
            Click <strong style={{ color: 'var(--accent-blue)' }}>"Row by Row"</strong> to convert to individual rows.
          </p>
        </div>
      )}

      {envMode === 'single' && (
        <>
          {envVars.length === 0 && (
            <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: 0 }}>
              No variables added yet.
            </p>
          )}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {envVars.map((ev, i) => (
              <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 8, alignItems: 'center' }}>
                <input
                  className="input"
                  placeholder="KEY"
                  value={ev.key}
                  onChange={e => handleEnvChange(i, 'key', e.target.value)}
                />
                <input
                  className="input"
                  placeholder="value"
                  value={ev.value}
                  onChange={e => handleEnvChange(i, 'value', e.target.value)}
                />
                <button
                  type="button"
                  onClick={() => removeEnvVar(i)}
                  style={{
                    background: 'none', border: 'none', cursor: 'pointer',
                    color: 'var(--text-muted)', fontSize: 18, lineHeight: 1,
                    padding: '4px 6px', borderRadius: 4,
                  }}
                  title="Remove"
                >×</button>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
});
