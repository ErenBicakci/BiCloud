import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity,
  ArrowRight,
  Cloud,
  Database,
  LockKeyhole,
  Moon,
  Network,
  Server,
  ShieldCheck,
  Sun,
  User,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { useTheme } from '../../context/theme';
import { authService } from '../../services/auth.service';
import { extractError } from '../../utils/common';
import { Button, Input } from '../../components/ui';

const REGISTER_PASSWORD_MIN_LENGTH = 8;

const LoginPage = () => {
  const { login } = useAuth();
  const { success } = useToast();
  const { theme, toggleTheme } = useTheme();
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
    if (!form.password) return 'Password is required.';
    if (!isLogin && form.password.length < REGISTER_PASSWORD_MIN_LENGTH) {
      return `Password must be at least ${REGISTER_PASSWORD_MIN_LENGTH} characters.`;
    }
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
    <main className="auth-page">
      <button
        type="button"
        className="auth-theme-toggle"
        onClick={toggleTheme}
        aria-label="Toggle theme"
      >
        {theme === 'light' ? <Moon size={16} /> : <Sun size={16} />}
        <span>{theme === 'light' ? 'Dark' : 'Light'}</span>
      </button>

      <section className="auth-shell">
        <aside className="auth-brand-panel" aria-label="BiCloud control plane preview">
          <div className="auth-brand-mark">
            <div className="auth-logo-box"><Cloud size={24} /></div>
            <div>
              <div className="auth-brand-name">BiCloud</div>
              <div className="auth-brand-meta">Cloud Console</div>
            </div>
          </div>

          <div className="auth-copy">
            <div className="auth-kicker"><ShieldCheck size={14} /> Private control plane</div>
            <h1>Container operations without leaving your own infrastructure.</h1>
            <p>
              Deploy services, route traffic through the gateway, and monitor workers from a single
              operational console.
            </p>
          </div>

          <div className="auth-console">
            <div className="auth-console-header">
              <div>
                <span className="auth-console-label">Pre-auth preview</span>
                <strong>No tenant data exposed</strong>
              </div>
              <span className="auth-live-pill">Live</span>
            </div>

            <div className="auth-topology">
              <TopologyNode icon={Server} title="Control plane" value="Access gated" tone="blue" />
              <ArrowRight size={18} />
              <TopologyNode icon={Network} title="Gateway" value="Private route" tone="green" />
              <ArrowRight size={18} />
              <TopologyNode icon={Database} title="Workers" value="Fleet ready" tone="cyan" />
            </div>

            <div className="auth-signal-grid">
              <SignalItem icon={Activity} label="Session scope" value="JWT" />
              <SignalItem icon={ShieldCheck} label="Default posture" value="Isolated" />
              <SignalItem icon={Network} label="Routing model" value="Gateway" />
            </div>
          </div>
        </aside>

        <section className="auth-card" aria-label={isLogin ? 'Sign in' : 'Register'}>
          <div className="auth-card-header">
            <div className="auth-logo-box compact"><Cloud size={19} /></div>
            <div>
              <p className="auth-panel-kicker">BiCloud account</p>
              <h2>{isLogin ? 'Sign in to console' : 'Create console account'}</h2>
            </div>
          </div>

          <div className="auth-tabs" role="tablist" aria-label="Authentication mode">
            <AuthTab active={isLogin} onClick={() => setIsLogin(true)}>Sign In</AuthTab>
            <AuthTab active={!isLogin} onClick={() => setIsLogin(false)}>Register</AuthTab>
          </div>

          <form className="auth-form" onSubmit={handleSubmit} autoComplete="off">
            {error && <div className="alert alert-error">{error}</div>}

            <Input
              label="Username"
              name="username"
              placeholder="Username"
              value={form.username}
              onChange={handleInputChange}
              icon={User}
              autoComplete="off"
              autoCorrect="off"
              spellCheck={false}
            />

            <Input
              label="Password"
              name="password"
              type="password"
              placeholder="Password"
              value={form.password}
              onChange={handleInputChange}
              icon={LockKeyhole}
              minLength={isLogin ? undefined : REGISTER_PASSWORD_MIN_LENGTH}
              autoComplete={isLogin ? 'current-password' : 'new-password'}
            />

            <Button type="submit" loading={loading} className="auth-submit">
              {isLogin ? 'Sign In' : 'Create Account'}
              {!loading && <ArrowRight size={16} />}
            </Button>
          </form>

          <div className="auth-card-footer">
            <span>Local-first infrastructure console</span>
            <span>2026</span>
          </div>
        </section>
      </section>
    </main>
  );
};

const AuthTab = ({ active, onClick, children }) => (
  <button
    type="button"
    role="tab"
    aria-selected={active}
    className={`auth-tab ${active ? 'active' : ''}`}
    onClick={onClick}
  >
    {children}
  </button>
);

const TopologyNode = ({ icon: Icon, title, value, tone }) => (
  <div className={`topology-node tone-${tone}`}>
    <div className="topology-icon">{React.createElement(Icon, { size: 18 })}</div>
    <div>
      <span>{title}</span>
      <strong>{value}</strong>
    </div>
  </div>
);

const SignalItem = ({ icon: Icon, label, value }) => (
  <div className="auth-signal">
    {React.createElement(Icon, { size: 15 })}
    <span>{label}</span>
    <strong>{value}</strong>
  </div>
);

export default LoginPage;
