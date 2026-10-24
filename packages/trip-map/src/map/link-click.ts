/**
 * Whether a click on one of the map's links is the map's to take: a plain one, rather
 * than one opening the link in another tab or window (⌘, Ctrl, Shift or Alt held, or a
 * button other than the main one), which is left to the browser.
 */
export function isPlainClick(event: MouseEvent) {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey
}
