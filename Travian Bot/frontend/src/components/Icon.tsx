/**
 * Tiny inline-SVG icon set in the Feather / Lucide visual style. Used by the
 * GlassHUD sidebar (nav items are 17×17 stroke-width 1.8). Centralised here
 * so the sidebar doesn't drown in inline SVG markup.
 *
 * Add icons by extending the IconName union and the switch below — keep
 * stroke="currentColor" so the parent's CSS color drives the icon tint.
 */

export type IconName =
  | 'grid'
  | 'users'
  | 'sliders'
  | 'home'
  | 'target'
  | 'check-square'
  | 'star'
  | 'flag'
  | 'search'
  | 'bar-chart'
  | 'shield'
  | 'plus'
  | 'list'
  | 'chevron-down'
  | 'chevron-right'
  | 'crosshair'
  | 'sword'
  | 'note'
  | 'message-circle'
  | 'skull'
  | 'wheat'
  | 'sparkles'
  | 'template'
  | 'sun'
  | 'moon'
  | 'monitor'
  | 'clock'
  | 'send'
  | 'map';

interface Props {
  name: IconName;
  size?: number;
  strokeWidth?: number;
  className?: string;
}

export default function Icon({
  name,
  size = 17,
  strokeWidth = 1.8,
  className,
}: Props) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      {paths(name)}
    </svg>
  );
}

function paths(name: IconName) {
  switch (name) {
    case 'grid':
      return (
        <>
          <rect x="3" y="3" width="7" height="7" rx="2" />
          <rect x="14" y="3" width="7" height="7" rx="2" />
          <rect x="3" y="14" width="7" height="7" rx="2" />
          <rect x="14" y="14" width="7" height="7" rx="2" />
        </>
      );
    case 'users':
      return (
        <>
          <path d="M16 19v-1.5a3 3 0 0 0-3-3H6a3 3 0 0 0-3 3V19" />
          <circle cx="9.5" cy="8" r="3" />
          <path d="M21 19v-1.4a3 3 0 0 0-2.4-2.9" />
          <path d="M15.5 5.2a3 3 0 0 1 0 5.6" />
        </>
      );
    case 'sliders':
      return (
        <>
          <line x1="4" y1="8" x2="20" y2="8" />
          <circle cx="9" cy="8" r="2.2" />
          <line x1="4" y1="16" x2="20" y2="16" />
          <circle cx="15" cy="16" r="2.2" />
        </>
      );
    case 'home':
      return (
        <>
          <path d="M4 11l8-7 8 7" />
          <path d="M6 10v9h12v-9" />
        </>
      );
    case 'target':
      return (
        <>
          <circle cx="12" cy="12" r="8" />
          <circle cx="12" cy="12" r="3" />
        </>
      );
    case 'clock':
      return (
        <>
          <circle cx="12" cy="12" r="8.5" />
          <path d="M12 7.5V12l3 2" />
        </>
      );
    case 'send':
      return (
        <>
          <path d="M22 2 11 13" />
          <path d="M22 2 15 22l-4-9-9-4Z" />
        </>
      );
    case 'map':
      return (
        <>
          <path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2Z" />
          <path d="M9 4v14" />
          <path d="M15 6v14" />
        </>
      );
    case 'check-square':
      return (
        <>
          <rect x="4" y="4" width="16" height="16" rx="3" />
          <path d="M8.5 12l2.4 2.4L16 9.2" />
        </>
      );
    case 'star':
      return (
        <path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 17l-5.2 2.6 1-5.8L3.5 9.7l5.9-.9z" />
      );
    case 'flag':
      return (
        <>
          <path d="M5 21V4" />
          <path d="M5 5h12l-2.5 3.5L17 12H5" />
        </>
      );
    case 'search':
      return (
        <>
          <circle cx="11" cy="11" r="6" />
          <line x1="20" y1="20" x2="16" y2="16" />
        </>
      );
    case 'bar-chart':
      return (
        <>
          <line x1="6" y1="20" x2="6" y2="11" />
          <line x1="12" y1="20" x2="12" y2="5" />
          <line x1="18" y1="20" x2="18" y2="14" />
        </>
      );
    case 'shield':
      return <path d="M12 2l8 4v6c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6z" />;
    case 'plus':
      return (
        <>
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </>
      );
    case 'list':
      return (
        <>
          <line x1="8" y1="7" x2="20" y2="7" />
          <line x1="8" y1="12" x2="20" y2="12" />
          <line x1="8" y1="17" x2="20" y2="17" />
          <circle cx="4" cy="7" r="1" />
          <circle cx="4" cy="12" r="1" />
          <circle cx="4" cy="17" r="1" />
        </>
      );
    case 'chevron-down':
      return <path d="M7 9l5 5 5-5" />;
    case 'chevron-right':
      return <path d="M9 6l6 6-6 6" />;
    case 'crosshair':
      return (
        <>
          <circle cx="12" cy="12" r="9" />
          <line x1="12" y1="3" x2="12" y2="7" />
          <line x1="12" y1="17" x2="12" y2="21" />
          <line x1="3" y1="12" x2="7" y2="12" />
          <line x1="17" y1="12" x2="21" y2="12" />
        </>
      );
    case 'sword':
      return (
        <>
          <path d="M14.5 17.5L3 6V3h3l11.5 11.5" />
          <path d="M13 19l6-6" />
          <path d="M16 16l4 4" />
          <path d="M19 21l2-2" />
        </>
      );
    case 'note':
      return (
        <>
          <path d="M6 4h9l4 4v12a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z" />
          <path d="M14 4v5h5" />
        </>
      );
    case 'message-circle':
      return (
        <path d="M21 12a8.5 8.5 0 1 1-3.4-6.8L21 4l-1.2 3.4A8.5 8.5 0 0 1 21 12z" />
      );
    case 'skull':
      return (
        <>
          <path d="M5 11a7 7 0 1 1 14 0v4l1 3h-3v2H8v-2H5l1-3z" />
          <circle cx="9" cy="12" r="1.2" />
          <circle cx="15" cy="12" r="1.2" />
        </>
      );
    case 'wheat':
      return (
        <>
          <path d="M12 22V8" />
          <path d="M12 8c-3-1-5-3-5-6 3 0 5 2 5 6z" />
          <path d="M12 8c3-1 5-3 5-6-3 0-5 2-5 6z" />
          <path d="M12 13c-3-1-5-3-5-6" />
          <path d="M12 13c3-1 5-3 5-6" />
        </>
      );
    case 'sparkles':
      return (
        <>
          <path d="M12 3l1.6 4.6L18 9l-4.4 1.4L12 15l-1.6-4.6L6 9l4.4-1.4z" />
          <path d="M19 14l.7 1.9L21 17l-1.3.5L19 20l-.7-1.5L17 17l1.3-.5z" />
        </>
      );
    case 'template':
      return (
        <>
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <line x1="3" y1="9" x2="21" y2="9" />
          <line x1="9" y1="9" x2="9" y2="21" />
        </>
      );
    case 'sun':
      return (
        <>
          <circle cx="12" cy="12" r="4" />
          <line x1="12" y1="3" x2="12" y2="5" />
          <line x1="12" y1="19" x2="12" y2="21" />
          <line x1="3" y1="12" x2="5" y2="12" />
          <line x1="19" y1="12" x2="21" y2="12" />
          <line x1="5.6" y1="5.6" x2="6.9" y2="6.9" />
          <line x1="17.1" y1="17.1" x2="18.4" y2="18.4" />
          <line x1="5.6" y1="18.4" x2="6.9" y2="17.1" />
          <line x1="17.1" y1="6.9" x2="18.4" y2="5.6" />
        </>
      );
    case 'moon':
      return <path d="M21 12.8A8 8 0 1 1 11.2 3a6.5 6.5 0 0 0 9.8 9.8z" />;
    case 'monitor':
      return (
        <>
          <rect x="3" y="4" width="18" height="13" rx="2" />
          <line x1="8" y1="21" x2="16" y2="21" />
          <line x1="12" y1="17" x2="12" y2="21" />
        </>
      );
  }
}
