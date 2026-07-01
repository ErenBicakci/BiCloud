import http from './http';

export const adminService = {
  listUsers:    ()         => http.get('/admin/users'),
  getUser:      (id)       => http.get(`/admin/users/${id}`),
  changeRole:   (id, role) => http.put(`/admin/users/${id}/role`, { role }),
  deleteUser:   (id)       => http.delete(`/admin/users/${id}`),
  resyncGateway: ()        => http.post('/admin/gateway/resync'),
};
