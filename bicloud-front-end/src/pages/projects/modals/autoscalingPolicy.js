export const AUTOSCALING_DEFAULTS = {
  autoscalingEnabled: false,
  minReplicas: '1',
  maxReplicas: '3',
  targetCpuPercent: '70',
  scaleDownCpuPercent: '30',
  scaleUpCooldownSeconds: '60',
  scaleDownCooldownSeconds: '300',
};

const AUTOSCALING_LIMITS = {
  minReplicas: { label: 'Min replicas', min: 1, max: 10 },
  maxReplicas: { label: 'Max replicas', min: 1, max: 10 },
  targetCpuPercent: { label: 'Target CPU', min: 1, max: 100 },
  scaleDownCpuPercent: { label: 'Scale-down CPU', min: 1, max: 99 },
  scaleUpCooldownSeconds: { label: 'Scale-up cooldown', min: 15, max: 3600 },
  scaleDownCooldownSeconds: { label: 'Scale-down cooldown', min: 15, max: 3600 },
};

export const autoscalingFormFromService = (service = {}) => ({
  autoscalingEnabled: Boolean(service.autoscalingEnabled ?? AUTOSCALING_DEFAULTS.autoscalingEnabled),
  minReplicas: String(service.minReplicas ?? AUTOSCALING_DEFAULTS.minReplicas),
  maxReplicas: String(service.maxReplicas ?? AUTOSCALING_DEFAULTS.maxReplicas),
  targetCpuPercent: String(service.targetCpuPercent ?? AUTOSCALING_DEFAULTS.targetCpuPercent),
  scaleDownCpuPercent: String(service.scaleDownCpuPercent ?? AUTOSCALING_DEFAULTS.scaleDownCpuPercent),
  scaleUpCooldownSeconds: String(service.scaleUpCooldownSeconds ?? AUTOSCALING_DEFAULTS.scaleUpCooldownSeconds),
  scaleDownCooldownSeconds: String(service.scaleDownCooldownSeconds ?? AUTOSCALING_DEFAULTS.scaleDownCooldownSeconds),
});

export const parseAutoscalingPolicy = (form, desiredReplicas = null) => {
  const parsed = {};

  for (const [field, limit] of Object.entries(AUTOSCALING_LIMITS)) {
    const result = parseIntegerInRange(form[field], limit);
    if (result.error) return { error: result.error };
    parsed[field] = result.value;
  }

  if (parsed.minReplicas > parsed.maxReplicas) {
    return { error: 'Min replicas cannot be greater than max replicas.' };
  }

  if (parsed.scaleDownCpuPercent >= parsed.targetCpuPercent) {
    return { error: 'Scale-down CPU must be lower than target CPU.' };
  }

  if (form.autoscalingEnabled && desiredReplicas !== null) {
    const desired = Number(desiredReplicas);
    if (Number.isFinite(desired) && (desired < parsed.minReplicas || desired > parsed.maxReplicas)) {
      return { error: 'Replicas must be within the autoscaling min/max range when autoscaling is enabled.' };
    }
  }

  return {
    value: {
      autoscalingEnabled: Boolean(form.autoscalingEnabled),
      ...parsed,
    },
  };
};

const parseIntegerInRange = (raw, limit) => {
  const text = String(raw ?? '').trim();
  const value = Number(text);

  if (!text) return { error: `${limit.label} is required.` };
  if (!Number.isInteger(value)) return { error: `${limit.label} must be a whole number.` };
  if (value < limit.min || value > limit.max) {
    return { error: `${limit.label} must be between ${limit.min} and ${limit.max}.` };
  }
  return { value };
};
