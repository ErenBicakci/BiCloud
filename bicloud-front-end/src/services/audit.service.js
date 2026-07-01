import http from './http';

/**
 * Audit event stream. Backend returns cursor-based pagination:
 * if beforeId is provided, events older than that id are returned ("load more").
 *
 * params: { action, beforeId, limit }
 */
export const auditService = {
  feed: (params = {}) =>
    http.get('/audit', { params }),

  projectFeed: (projectId, params = {}) =>
    http.get(`/audit/project/${projectId}`, { params }),
};
