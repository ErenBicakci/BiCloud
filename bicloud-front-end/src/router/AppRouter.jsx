import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Layout } from '../components/layout/Layout';

const DashboardPage     = React.lazy(() => import('../pages/dashboard/DashboardPage'));
const ProjectsPage      = React.lazy(() => import('../pages/projects/ProjectsPage'));
const ProjectDetailPage = React.lazy(() => import('../pages/projects/ProjectDetailPage'));
const ServiceDetailPage = React.lazy(() => import('../pages/projects/ServiceDetailPage'));
const LoginPage         = React.lazy(() => import('../pages/auth/LoginPage'));
const AuditPage         = React.lazy(() => import('../pages/audit/AuditPage'));

const AdminOverviewPage = React.lazy(() => import('../pages/admin/AdminOverviewPage'));
const AdminUsersPage    = React.lazy(() => import('../pages/admin/AdminUsersPage'));
const WorkersPage       = React.lazy(() => import('../pages/workers/WorkersPage'));
const WorkerDetailPage  = React.lazy(() => import('../pages/workers/WorkerDetailPage'));

const PrivateRoute = ({ children }) => {
  const { isAuthenticated } = useAuth();
  return isAuthenticated
    ? <Layout>{children}</Layout>
    : <Navigate to="/login" replace />;
};

const AdminRoute = ({ children }) => {
  const { isAuthenticated, isAdmin } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (!isAdmin)         return <Navigate to="/"      replace />;
  return <Layout>{children}</Layout>;
};

export const AppRouter = () => {
  const { isAuthenticated } = useAuth();

  return (
    <React.Suspense fallback={<PageLoader />}>
      <Routes>
        <Route path="/login" element={isAuthenticated ? <Navigate to="/" replace /> : <LoginPage />} />

        {/* User routes */}
        <Route path="/"              element={<PrivateRoute><DashboardPage /></PrivateRoute>} />
        <Route path="/projects"      element={<PrivateRoute><ProjectsPage /></PrivateRoute>} />
        <Route path="/projects/:id"                       element={<PrivateRoute><ProjectDetailPage /></PrivateRoute>} />
        <Route path="/projects/:id/services/:serviceId"   element={<PrivateRoute><ServiceDetailPage /></PrivateRoute>} />
        <Route path="/activity"      element={<PrivateRoute><AuditPage /></PrivateRoute>} />
        {/* Admin-only routes */}
        <Route path="/admin"          element={<AdminRoute><AdminOverviewPage /></AdminRoute>} />
        <Route path="/admin/users"    element={<AdminRoute><AdminUsersPage /></AdminRoute>} />
        <Route path="/admin/workers"           element={<AdminRoute><WorkersPage /></AdminRoute>} />
        <Route path="/admin/workers/:workerId"  element={<AdminRoute><WorkerDetailPage /></AdminRoute>} />

        {/* Legacy redirect */}
        <Route path="/workers" element={<Navigate to="/admin/workers" replace />} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </React.Suspense>
  );
};

const PageLoader = () => (
  <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
    <div className="spinner spinner-lg" />
  </div>
);
