export const extractError = (err) => {
  const data = err?.response?.data;
  if (typeof data === 'string') return data;
  if (data?.message) return data.message;
  if (data?.error) return data.error;
  if (err?.message) return err.message;
  return 'An unexpected error occurred.';
};

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
