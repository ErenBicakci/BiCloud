import { useCallback, useEffect, useRef, useState } from 'react';

const writeClipboard = async (text) => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  // navigator.clipboard is only available on HTTPS or localhost
  const area = document.createElement('textarea');
  area.value = text;
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  document.execCommand('copy');
  document.body.removeChild(area);
};

export const useCopy = (duration = 1500) => {
  const [copied, setCopied] = useState(null);
  const timer = useRef(null);

  useEffect(() => () => clearTimeout(timer.current), []);

  const copy = useCallback(async (text, key = true) => {
    try {
      await writeClipboard(text);
    } catch {
      return;
    }
    setCopied(key);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(null), duration);
  }, [duration]);

  return [copied, copy];
};
