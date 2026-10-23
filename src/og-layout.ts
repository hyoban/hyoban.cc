export const OG_IMAGE_WIDTH = 1200
export const OG_IMAGE_HEIGHT = 630

export const OG_COLORS = {
  bg: '#fdfdfc',
  border: '#e9e9e7',
  tx: '#21201c',
  tx2: '#63635e',
  tx3: '#73736c',
}

export type OgNode = { type: string; props: Record<string, unknown> }

export function h(
  type: string,
  style: Record<string, unknown>,
  children?: unknown,
  props: Record<string, unknown> = {},
): OgNode {
  return { type, props: { ...props, style, children } }
}
