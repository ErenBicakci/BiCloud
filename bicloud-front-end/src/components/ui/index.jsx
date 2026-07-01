import React from 'react';

export const Button = ({ 
  children, 
  variant = 'primary', 
  size = 'md', 
  loading = false, 
  disabled = false, 
  icon: Icon,
  className = '',
  ...props 
}) => {
  const baseClass = `btn btn-${variant} ${size === 'sm' ? 'btn-sm' : ''} ${className}`;
  
  return (
    <button 
      className={baseClass} 
      disabled={disabled || loading} 
      {...props}
    >
      {loading && <div className="spinner" style={{ width: 14, height: 14, borderWidth: 2 }}/>}
      {!loading && Icon && <Icon size={16} />}
      {children}
    </button>
  );
};

export const Badge = ({ children, variant = 'gray', className = '' }) => {
  return (
    <span className={`badge badge-${variant} ${className}`}>
      {children}
    </span>
  );
};

export const Spinner = ({ size = 'md', className = '' }) => {
  return (
    <div className={`spinner ${size === 'lg' ? 'spinner-lg' : ''} ${className}`} />
  );
};

export const Card = ({ children, glow = false, className = '', ...props }) => {
  return (
    <div className={`card ${glow ? 'card-glow' : ''} ${className}`} {...props}>
      {children}
    </div>
  );
};

export const Input = ({ label, error, hint, icon: Icon, className = '', ...props }) => {
  return (
    <div className="form-group">
      {label && <label className="form-label">{label}</label>}
      <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
        {Icon && (
          <span style={{
            position: 'absolute', left: 10, pointerEvents: 'none',
            color: 'var(--text-muted)', display: 'flex', alignItems: 'center'
          }}>
            <Icon size={15} />
          </span>
        )}
        <input
          className={`input ${error ? 'error' : ''} ${className}`}
          style={Icon ? { paddingLeft: 32 } : undefined}
          {...props}
        />
      </div>
      {error && <span className="form-error">{error}</span>}
      {!error && hint && <span className="form-hint">{hint}</span>}
    </div>
  );
};
