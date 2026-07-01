import http from './http';

// /workers/**     -> JWT-protected, for the frontend
// /api/workers/**  -> X-Api-Key protected, for internal worker-CP communication
export const workerService = {
  list:   ()         => http.get('/workers'),
  get:    (id)       => http.get(`/workers/${id}`),

  // Maintenance mode (drain) — ADMIN only. enabled=true: no new jobs assigned,
  // existing containers keep running.
  setMaintenance: (id, enabled) =>
    http.put(`/workers/${id}/maintenance`, null, { params: { enabled } }),
};
