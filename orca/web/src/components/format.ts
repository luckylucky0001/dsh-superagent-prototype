import dayjs from 'dayjs';

/** Absolute time in the browser's time zone. */
export function fmtTime(ms: number | null | undefined): string {
  if (!ms) return '—';
  const d = dayjs(ms);
  return d.isSame(dayjs(), 'day') ? d.format('HH:mm:ss') : d.format('YYYY-MM-DD HH:mm');
}

export function fmtDateTime(ms: number | null | undefined): string {
  if (!ms) return '—';
  return dayjs(ms).format('YYYY-MM-DD HH:mm:ss');
}

export function fromNow(ms: number | null | undefined): string {
  if (!ms) return '—';
  return dayjs(ms).fromNow();
}

export function fmtDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms < 0) return '—';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

export function durationBetween(start: number | null | undefined, end: number | null | undefined): number | null {
  if (!start) return null;
  return (end ?? Date.now()) - start;
}

export function fmtTokens(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—';
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

/** The time zone the UI shows times in. */
export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    return 'UTC';
  }
}

export function shortPath(p: string, keep = 3): string {
  const parts = p.split('/').filter(Boolean);
  if (parts.length <= keep) return p;
  return '…/' + parts.slice(-keep).join('/');
}

export function prettyJson(v: unknown): string {
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}
