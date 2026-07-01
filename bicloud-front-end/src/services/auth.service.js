import http from './http';

export const authService = {
  register:      (data) => http.post('/auth/register', data),
  login:         (data) => http.post('/auth/login', data),
  me:            ()     => http.get('/auth/me'),
  adminRegister: (data) => http.post('/auth/admin/register', data),
};
