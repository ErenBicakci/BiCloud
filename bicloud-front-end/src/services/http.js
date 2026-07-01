import axios from 'axios';
import { getToken, clearAuth } from '../utils/auth';

const http = axios.create({
  baseURL: '/api-cp',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers['Authorization'] = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      // Only log out if the token is genuinely invalid.
      // Prevent infinite redirect loop if already on the login page.
      const isOnLoginPage = window.location.pathname === '/login';
      if (!isOnLoginPage) {
        clearAuth();
        window.location.href = '/login';
      }
    }
    return Promise.reject(err);
  }
);

export default http;
