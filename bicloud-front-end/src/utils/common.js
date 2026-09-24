export const extractError = (err) => {
  const data = err?.response?.data;
  if (typeof data === 'string') return data;
  if (data?.message) return data.message;
  if (data?.error) return data.error;
  if (err?.message) return err.message;
  return 'An unexpected error occurred.';
};

const DEPLOY_COOLDOWN_FAILURES = 5;
const DEPLOY_COOLDOWN_MS = 5 * 60 * 1000;

export const isInDeployCooldown = (service) =>
  service.consecutiveDeployFailures >= DEPLOY_COOLDOWN_FAILURES
  && Boolean(service.lastDeployFailureAt)
  && Date.now() - new Date(service.lastDeployFailureAt).getTime() < DEPLOY_COOLDOWN_MS;

export const formatDate = (dateString) => {
  if (!dateString) return '–';
  return new Date(dateString).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  });
};
