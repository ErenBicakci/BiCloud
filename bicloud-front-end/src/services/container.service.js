import http from './http';

export const containerService = {
  listByProject: (projectId)            => http.get(`/containers/project/${projectId}`),
  stop:          (instanceId)           => http.post(`/containers/${instanceId}/stop`),
  remove:        (instanceId)           => http.delete(`/containers/${instanceId}`),
  logs:          (instanceId, tail=100) => http.get(`/containers/${instanceId}/logs?tail=${tail}`),

  /**
   * Live CPU/RAM measurements + sparkline history for RUNNING containers.
   * If serviceName is provided, only returns containers for that service.
   */
  metrics: (projectId, serviceName) =>
    http.get(`/containers/project/${projectId}/metrics`, { params: { serviceName } }),

  /**
   * Search with server-side filtering / pagination / sorting support.
   * params: { serviceName, status, search, sortBy, sortDir, page, size }
   * All parameters are optional.
   */
  search: (projectId, params = {}) =>
    http.get(`/containers/project/${projectId}/search`, { params }),
};
