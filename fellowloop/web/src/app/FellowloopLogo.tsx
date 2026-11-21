export function FellowloopLogo({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-label="Fellowloop">
      <rect width="32" height="32" rx="8" fill="#0f5fd7" />
      <path d="M7 19c3-7 10-10 18-8-2 1-3 2-3 4 0 4-4 7-9 7-2 0-4-1-6-3zm13-5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z" fill="#fff" />
    </svg>
  );
}
