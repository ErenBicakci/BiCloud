import React from 'react';

/**
 * Dependency-free mini SVG line chart. Used for short history
 * visualizations in metric cards and table cells.
 *
 * Props:
 *   data   – number array (oldest to newest)
 *   width / height – pixel dimensions
 *   color  – line color (CSS variables supported)
 *   max    – fixed upper bound (defaults to series maximum)
 */
export const Sparkline = ({
  data,
  width = 120,
  height = 36,
  color = 'var(--accent-blue)',
  max,
}) => {
  if (!data || data.length < 2) {
    return (
      <svg width={width} height={height} aria-hidden="true">
        <line
          x1="0" y1={height / 2} x2={width} y2={height / 2}
          stroke="var(--border-subtle)" strokeWidth="1" strokeDasharray="3 3"
        />
      </svg>
    );
  }

  const maxV = Math.max(max ?? Math.max(...data), 0.001);
  const pad = 2;
  const innerH = height - pad * 2;

  const points = data.map((v, i) => {
    const x = (i / (data.length - 1)) * width;
    const y = pad + innerH - (Math.min(v, maxV) / maxV) * innerH;
    return [x, y];
  });

  const line = points.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `0,${height} ${line} ${width},${height}`;

  return (
    <svg width={width} height={height} aria-hidden="true">
      <polygon points={area} fill={color} opacity="0.12" />
      <polyline
        points={line}
        fill="none"
        stroke={color}
        strokeWidth="1.5"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
};
