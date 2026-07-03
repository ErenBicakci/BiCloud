/* eslint-disable react-refresh/only-export-components */
import React, { createContext, useContext, useState, useCallback, useMemo } from 'react';
import { saveAuth as saveAuthUtil, clearAuth as clearAuthUtil, getToken, getUser as getStoredUser } from '../utils/auth';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(getStoredUser());
  const [token, setToken] = useState(getToken());

  const login = useCallback((tokenVal, userData) => {
    saveAuthUtil(tokenVal, userData);
    setToken(tokenVal);
    setUser(userData);
  }, []);

  const logout = useCallback(() => {
    clearAuthUtil();
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo(() => ({
    user,
    token,
    login,
    logout,
    isAdmin: user?.role === 'ADMIN',
    isAuthenticated: !!token
  }), [user, token, login, logout]);

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within an AuthProvider');
  return context;
};
