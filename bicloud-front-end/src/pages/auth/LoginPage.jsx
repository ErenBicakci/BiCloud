import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { authService } from '../../services/auth.service';
import { extractError } from '../../utils/common';
import { Button, Input, Card } from '../../components/ui';
import { Hexagon, Lock, User } from 'lucide-react';

const LoginPage = () => {
  const { login } = useAuth();
  const { success } = useToast();
  const navigate = useNavigate();
  
  const [isLogin, setIsLogin] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [form, setForm] = useState({ username: '', password: '' });

  const handleInputChange = (e) => {
    setForm(prev => ({ ...prev, [e.target.name]: e.target.value }));
    setError('');
  };

  const validate = () => {
    if (!form.username.trim()) return 'Username is required.';
    if (form.password.length < 6) return 'Password must be at least 6 characters.';
    return null;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    const validationError = validate();
    if (validationError) {
      setError(validationError);
      return;
    }

    setLoading(true);
    try {
      const service = isLogin ? authService.login : authService.register;
      const { data } = await service(form);
      
      login(data.token, { username: data.username, role: data.role });
      success(`Welcome, ${data.username}!`);
      navigate('/');
    } catch (err) {
      setError(extractError(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      backgroundColor: 'var(--bg-base)',
      backgroundImage: `
        radial-gradient(circle at 50% -20%, rgba(56, 139, 253, 0.25) 0%, transparent 60%),
        linear-gradient(to right, rgba(255, 255, 255, 0.03) 1px, transparent 1px),
        linear-gradient(to bottom, rgba(255, 255, 255, 0.03) 1px, transparent 1px)
      `,
      backgroundSize: '100% 100%, 40px 40px, 40px 40px',
      backgroundPosition: '0 0, center center, center center',
      padding: 20,
      position: 'relative'
    }}>
      <DataStreams />
      
      <div className="fade-in" style={{ width: '100%', maxWidth: 400, position: 'relative', zIndex: 1 }}>
        {/* Logo Section */}
        <div style={{ textAlign: 'center', marginBottom: 40 }}>
          <div style={{ 
            display: 'inline-flex', padding: 16, 
            background: 'linear-gradient(135deg, rgba(56,139,253,.15), rgba(57,197,207,.05))', 
            borderRadius: 20, border: '1px solid rgba(56,139,253,.3)', 
            marginBottom: 20, color: 'var(--accent-cyan)',
            boxShadow: '0 0 40px rgba(56,139,253,.2), inset 0 0 20px rgba(56,139,253,.1)'
          }}>
            <Hexagon size={44} strokeWidth={1.5} />
          </div>
          <h1 style={{ fontSize: '2.2rem', fontWeight: 800, letterSpacing: '-0.03em', marginBottom: 10 }}>
            <span className="gradient-text">BiCloud</span>
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.95rem', fontWeight: 500 }}>Modern Container Management Platform</p>
        </div>

        <Card glow style={{ padding: 0, overflow: 'hidden' }}>
          {/* Tabs */}
          <div style={{ display: 'flex' }}>
            <Tab active={isLogin} onClick={() => setIsLogin(true)}>Sign In</Tab>
            <Tab active={!isLogin} onClick={() => setIsLogin(false)}>Register</Tab>
          </div>

          <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20 }}>
            {error && <div className="alert alert-error">{error}</div>}
            
            <Input 
              label="Username"
              name="username"
              placeholder="Enter your username"
              value={form.username}
              onChange={handleInputChange}
              icon={User}
            />

            <Input 
              label="Password"
              name="password"
              type="password"
              placeholder="••••••••"
              value={form.password}
              onChange={handleInputChange}
              icon={Lock}
            />

            <Button type="submit" loading={loading} style={{ width: '100%', marginTop: 8 }}>
              {isLogin ? 'Sign In' : 'Register'}
            </Button>
          </form>
        </Card>

        <p style={{ textAlign: 'center', marginTop: 24, fontSize: '0.75rem', color: 'var(--text-muted)' }}>
          © 2026 BiCloud Infrastructure Services
        </p>
      </div>
    </div>
  );
};

const Tab = ({ active, onClick, children }) => (
  <button 
    onClick={onClick}
    style={{
      flex: 1, padding: '16px', border: 'none', background: active ? 'transparent' : 'rgba(255,255,255,0.02)',
      color: active ? 'var(--accent-blue)' : 'var(--text-muted)', fontWeight: 600, fontSize: '0.875rem',
      borderBottom: `2px solid ${active ? 'var(--accent-blue)' : 'transparent'}`,
      cursor: 'pointer', transition: 'all 0.2s'
    }}
  >
    {children}
  </button>
);

const DataStreams = () => {
  // Random streams aligned to the 40px grid.
  // Background is 'center center', so grid lines are offset 20px from 50%.
  const streams = [];
  
  // Horizontal streams (X axis)
  const yOffsets = [-260, -140, -20, 100, 220, 340];
  yOffsets.forEach((y, i) => {
    streams.push(
      <div key={`hx-${i}`} className="data-stream data-stream-x" style={{
        top: `calc(50% + ${y}px)`,
        animationDelay: `${Math.random() * 4}s`,
        animationDuration: `${3 + Math.random() * 3}s`
      }} />
    );
  });

  // Vertical streams (Y axis)
  const xOffsets = [-340, -180, -60, 60, 180, 300, 420];
  xOffsets.forEach((x, i) => {
    streams.push(
      <div key={`vy-${i}`} className="data-stream data-stream-y" style={{
        left: `calc(50% + ${x}px)`,
        animationDelay: `${Math.random() * 4}s`,
        animationDuration: `${4 + Math.random() * 4}s`
      }} />
    );
  });

  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', pointerEvents: 'none', zIndex: 0 }}>
      {streams}
    </div>
  );
};

export default LoginPage;
