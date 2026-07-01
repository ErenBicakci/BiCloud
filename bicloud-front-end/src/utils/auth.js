const TOKEN_KEY = 'bc_token';
const USER_KEY  = 'bc_user';

export const saveAuth = (token, user) => {
  sessionStorage.setItem(TOKEN_KEY, token);
  sessionStorage.setItem(USER_KEY, JSON.stringify(user));
};

export const clearAuth = () => {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(USER_KEY);
};

export const getToken = () => sessionStorage.getItem(TOKEN_KEY);

export const getUser = () => {
  try {
    const user = sessionStorage.getItem(USER_KEY);
    return user ? JSON.parse(user) : null;
  } catch {
    return null;
  }
};

export const isLoggedIn = () => !!getToken();

export const isAdmin = () => getUser()?.role === 'ADMIN';
