import React from 'react';
import { createPortal } from 'react-dom';

export const Modal = ({ 
  isOpen, 
  onClose, 
  title, 
  children, 
  footer,
  maxWidth = 480 
}) => {
  if (!isOpen) return null;

  return createPortal(
    <div className="modal-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal fade-in" style={{ maxWidth }}>
        <div className="modal-header">
          {title && <h3 className="modal-title">{title}</h3>}
          <button className="btn-icon" onClick={onClose}>✕</button>
        </div>
        
        <div className="modal-content">
          {children}
        </div>

        {footer && (
          <div className="modal-footer">
            {footer}
          </div>
        )}
      </div>
    </div>,
    document.body
  );
};
