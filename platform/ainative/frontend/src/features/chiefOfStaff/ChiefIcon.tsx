interface ChiefIconProps { size?: number; className?: string; }

/** AI 管家标识:四角星(sparkle)主星 + 双伴星,AI 产品的通用高级语言。 */
export function ChiefIcon({ size = 24, className }: ChiefIconProps) {
  return (
    <svg className={className ? `cf-chief-icon ${className}` : 'cf-chief-icon'} width={size} height={size} viewBox="0 0 24 24"
      aria-hidden="true" focusable="false" shapeRendering="geometricPrecision">
      <path d="M12 3.2c.5 3.9 2.4 5.8 6.3 6.3-3.9.5-5.8 2.4-6.3 6.3-.5-3.9-2.4-5.8-6.3-6.3 3.9-.5 5.8-2.4 6.3-6.3Z"
        fill="currentColor" />
      <path d="M18.6 13.4c.26 2 1.24 3.15 3.15 3.15-1.91.26-2.89 1.24-3.15 3.15-.26-1.91-1.24-2.89-3.15-3.15 1.91-.26 2.89-1.24 3.15-3.15Z"
        fill="currentColor" opacity=".75" />
      <circle cx="6.1" cy="17.9" r="1.35" fill="currentColor" opacity=".55" />
    </svg>
  );
}
