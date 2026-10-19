// The colours the map and the player draw with: the page's theme, read from its
// custom properties in the browser (dom-theme.ts), and a colour for each year places
// were visited in.

export type Theme = Readonly<{
  /** Lines and dots: the text colour. */
  ink: string
  border: string
  /** Secondary text. */
  muted: string
  /** The background. */
  paper: string
}>

export const YEAR_COLORS = [
  '#3b82f6',
  '#8b5cf6',
  '#f59e0b',
  '#10b981',
  '#ef4444',
  '#06b6d4',
] as const

/**
 * Gives each year a colour from the palette, in order from the earliest year, so the
 * legend and the dots agree; years past the palette's end start it over.
 */
export function createYearPalette(
  years: Iterable<number>,
  palette: readonly string[] = YEAR_COLORS,
) {
  const sorted = [...new Set(years)].sort((first, second) => first - second)
  const colors = new Map(sorted.map((year, index) => [year, palette[index % palette.length]!]))

  return (year: number) => colors.get(year) ?? palette[0]!
}
