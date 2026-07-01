import http from './http';

export const projectService = {
  list:           ()                   => http.get('/project'),
  get:            (id)                 => http.get(`/project/${id}`),
  create:         (data)               => http.post('/project', data),
  delete:         (id)                 => http.delete(`/project/${id}`),
  deploy:         (id)                 => http.post(`/project/${id}/deploy`),
  undeploy:       (id)                 => http.post(`/project/${id}/undeploy`),
  addImage:       (data)               => http.post('/project/image', data),
  updateImage:    (imageId, data)      => http.put(`/project/image/${imageId}`, data),
  deleteImage:    (imageId)            => http.delete(`/project/image/${imageId}`),
  scale:          (imageId, replicas)  => http.put(`/project/image/${imageId}/scale`, { replicas }),
  resetFailures:  (imageId)            => http.post(`/project/image/${imageId}/reset-failures`),
};
