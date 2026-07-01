import React, { useState, useEffect, useCallback } from 'react';
import { adminService } from '../../services/admin.service';
import { extractError, formatDate } from '../../utils/common';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { useNavigate } from 'react-router-dom';
import { Card, Badge, Button, Input, Spinner } from '../../components/ui';
import { Modal } from '../../components/ui/Modal';
import { Shield, User, Search, Trash2, UserPlus, UserMinus } from 'lucide-react';

export default function AdminUsersPage() {
  const { user: me, isAdmin } = useAuth();
  const { error, success } = useToast();
  const navigate = useNavigate();

  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [deletingUser, setDeletingUser] = useState(null);

  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await adminService.listUsers();
      setUsers(data || []);
    } catch (err) {
      error(extractError(err));
      navigate('/');
    } finally {
      setLoading(false);
    }
  }, [error, navigate]);

  useEffect(() => {
    if (!isAdmin) return navigate('/');
    loadUsers();
  }, [isAdmin, loadUsers, navigate]);

  const handleRoleToggle = async (user) => {
    if (user.id === me?.id) return error('You cannot change your own role.');
    const newRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN';
    
    if (!window.confirm(`User ${user.username} will be changed to ${newRole} role. Are you sure?`)) return;

    try {
      await adminService.changeRole(user.id, newRole);
      success('User role updated.');
      loadUsers();
    } catch (err) {
      error(extractError(err));
    }
  };

  const handleDelete = async () => {
    if (!deletingUser) return;
    try {
      await adminService.deleteUser(deletingUser.id);
      success('User deleted successfully.');
      setDeletingUser(null);
      loadUsers();
    } catch (err) {
      error(extractError(err));
    }
  };

  const filteredUsers = users.filter(u => 
    u.username.toLowerCase().includes(searchTerm.toLowerCase())
  );

  if (loading && users.length === 0) return <div className="page-loader"><Spinner size="lg" /></div>;

  return (
    <div style={{ padding: '32px' }}>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 32 }}>
        <div>
          <h1 style={{ fontSize: '1.75rem', fontWeight: 800, marginBottom: 4 }}>User Management</h1>
          <p style={{ color: 'var(--text-muted)' }}>Manage all users and their permissions in the system.</p>
        </div>
        <Badge variant="purple">Admin Panel</Badge>
      </header>

      <div style={{ marginBottom: 24, maxWidth: 400 }}>
        <Input 
          placeholder="Search username..." 
          value={searchTerm} 
          onChange={(e) => setSearchTerm(e.target.value)}
          icon={Search}
        />
      </div>

      <Card style={{ padding: 0 }}>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>User</th>
                <th>Role</th>
                <th>Registration Date</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredUsers.map(user => (
                <tr key={user.id}>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <div style={{ 
                        width: 32, height: 32, borderRadius: '50%', background: 'var(--bg-elevated)', 
                        display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700, 
                        color: user.role === 'ADMIN' ? 'var(--accent-purple)' : 'var(--accent-blue)',
                        border: '1px solid var(--border-subtle)'
                      }}>
                        {user.username[0].toUpperCase()}
                      </div>
                      <div>
                        <div style={{ fontWeight: 600 }}>{user.username}</div>
                        <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>{user.id}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <Badge variant={user.role === 'ADMIN' ? 'purple' : 'blue'}>
                      {user.role}
                    </Badge>
                  </td>
                  <td>{formatDate(user.createdAt)}</td>
                  <td>
                    <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                      <Button 
                        size="sm" variant="ghost" 
                        onClick={() => handleRoleToggle(user)}
                        disabled={user.id === me?.id}
                        icon={user.role === 'ADMIN' ? UserMinus : UserPlus}
                      >
                        {user.role === 'ADMIN' ? 'Downgrade' : 'Upgrade'}
                      </Button>
                      <Button 
                        size="sm" variant="danger" 
                        onClick={() => setDeletingUser(user)}
                        disabled={user.id === me?.id}
                        icon={Trash2}
                      />
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Modal 
        isOpen={!!deletingUser} 
        onClose={() => setDeletingUser(null)} 
        title="Delete User"
        maxWidth={400}
      >
        <p style={{ marginBottom: 20 }}>
          Are you sure you want to delete user <strong>{deletingUser?.username}</strong>? 
          This action cannot be undone and all their projects may be deleted.
        </p>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <Button variant="ghost" onClick={() => setDeletingUser(null)}>Cancel</Button>
          <Button variant="danger" onClick={handleDelete}>Yes, Delete</Button>
        </div>
      </Modal>
    </div>
  );
}
