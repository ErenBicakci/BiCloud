import React from 'react';
import { Activity, Gauge, Layers, Timer, TrendingDown, TrendingUp } from 'lucide-react';
import { Badge, Input } from '../../../components/ui';

export const AutoscalingPolicyFields = ({ form, onToggle, onChange }) => (
  <div className="autoscale-fields">
    <label className={`policy-option ${form.autoscalingEnabled ? 'active' : ''}`}>
      <input
        type="checkbox"
        checked={form.autoscalingEnabled}
        onChange={e => onToggle(e.target.checked)}
      />
      <div>
        <div className="policy-option-title">
          <Activity size={15} />
          Scale from CPU utilization
        </div>
        <div className="policy-option-desc">
          Adjusts desired replicas from fresh container CPU samples.
        </div>
      </div>
      <Badge variant={form.autoscalingEnabled ? 'blue' : 'gray'}>
        {form.autoscalingEnabled ? 'Enabled' : 'Manual'}
      </Badge>
    </label>

    <div className="field-grid three">
      <Input
        label="Min replicas"
        name="minReplicas"
        type="number"
        min="1"
        max="10"
        value={form.minReplicas}
        onChange={onChange}
        icon={Layers}
      />
      <Input
        label="Max replicas"
        name="maxReplicas"
        type="number"
        min="1"
        max="10"
        value={form.maxReplicas}
        onChange={onChange}
        icon={Layers}
      />
      <Input
        label="Target CPU (%)"
        name="targetCpuPercent"
        type="number"
        min="1"
        max="100"
        value={form.targetCpuPercent}
        onChange={onChange}
        icon={Gauge}
      />
      <Input
        label="Scale-down CPU (%)"
        name="scaleDownCpuPercent"
        type="number"
        min="1"
        max="99"
        value={form.scaleDownCpuPercent}
        onChange={onChange}
        icon={TrendingDown}
      />
      <Input
        label="Up cooldown (s)"
        name="scaleUpCooldownSeconds"
        type="number"
        min="15"
        max="3600"
        value={form.scaleUpCooldownSeconds}
        onChange={onChange}
        icon={TrendingUp}
      />
      <Input
        label="Down cooldown (s)"
        name="scaleDownCooldownSeconds"
        type="number"
        min="15"
        max="3600"
        value={form.scaleDownCooldownSeconds}
        onChange={onChange}
        icon={Timer}
      />
    </div>
  </div>
);
