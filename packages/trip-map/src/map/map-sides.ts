import type { Map as MapboxMap, PaddingOptions } from 'mapbox-gl'
import type { MapElements } from '../elements.ts'
import type { Sheet } from './sheet.ts'

// What's open on the map's side, and the room the map keeps clear of it: a place's drawer,
// or a list beside it (the trip list, a trip's sidebar, or the Moments), on the right, or
// as a sheet from the bottom on a phone.

// The least room, in pixels, the map frames places in beside the trip list.
const MIN_FRAMED = 240

type Side = { isOpen: boolean; sheet: Sheet }

export type MapSides = ReturnType<typeof createMapSides>

export function createMapSides({
  elements,
  getMap,
  getParts,
  isNarrow,
  isTripListUrl,
}: {
  elements: MapElements
  getMap: () => MapboxMap | undefined
  /** What may be open beside the map; called only once they're all made. */
  getParts: () => {
    drawer: { activeId: string | undefined; sheet: Sheet }
    momentList: Side | null | undefined
    tripDetails: Side | null | undefined
    tripList: Side | null | undefined
  }
  isNarrow: () => boolean
  /** Whether the address is the trip list's. */
  isTripListUrl: () => boolean
}) {
  /** The list open beside the map: the trip list on its address, a trip's sidebar, or the Moments. */
  function getOpenList() {
    const { momentList, tripDetails, tripList } = getParts()

    return elements.tripList && isTripListUrl() && tripList?.isOpen
      ? elements.tripList
      : tripDetails?.isOpen
        ? elements.tripDetails
        : momentList?.isOpen
          ? elements.moments
          : undefined
  }

  /** What of the map a list beside it covers. */
  function getListInset(): { bottom?: number; right?: number } {
    const list = getOpenList()

    if (!list) {
      return {}
    }

    // The list and its margin, at the bottom on a phone and on the right otherwise;
    // nothing if what it leaves is too small to frame anything in.
    const inset: { bottom?: number; right?: number } = isNarrow()
      ? { bottom: getShownHeight(list) + 12 }
      : { right: list.offsetWidth + 16 }
    const container = getMap()?.getContainer() ?? elements.canvas
    const room = Math.min(
      container.clientWidth - (inset.right ?? 0),
      container.clientHeight - (inset.bottom ?? 0),
    )

    return room < MIN_FRAMED ? {} : inset
  }

  /** What framing every place keeps clear of: a list beside the map, and the legend. */
  function getFramingInset() {
    const legend = elements.legend?.getBoundingClientRect()
    const container = (getMap()?.getContainer() ?? elements.canvas).getBoundingClientRect()

    return {
      ...getListInset(),
      ...(legend && legend.height > 0 ? { legend: container.bottom - legend.top } : {}),
    }
  }

  /**
   * What of a sheet shows on a phone: as tall as the screen once it can be pulled up, only
   * the part it rests at is in front of the map (see sheet.ts).
   */
  function getShownHeight(side: HTMLElement) {
    const { drawer, momentList, tripDetails, tripList } = getParts()
    const sheet = [drawer.sheet, tripList?.sheet, tripDetails?.sheet, momentList?.sheet].find(
      (each) => each?.popup === side,
    )

    return sheet ? sheet.getVisibleSize() : side.offsetHeight
  }

  /** What's open on the map's side, if anything: a place's drawer, or a list. */
  function getOpenSide() {
    return getParts().drawer.activeId ? elements.drawer : getOpenList()
  }

  /** Room kept on the map for what's open on its side, and around the rest. */
  function getClearPadding(): Required<PaddingOptions> {
    const side = getOpenSide()
    return side ? getSidePadding(side) : { bottom: 32, left: 32, right: 32, top: 80 }
  }

  /** Room kept on the map for what sits on its side: the drawer, or a list. */
  function getSidePadding(side: HTMLElement): Required<PaddingOptions> {
    if (isNarrow()) {
      return {
        bottom: Math.min(getShownHeight(side) + 32, window.innerHeight * 0.82),
        left: 32,
        right: 32,
        top: 80,
      }
    }

    return { bottom: 32, left: 32, right: side.offsetWidth + 32, top: 80 }
  }

  return { getClearPadding, getFramingInset, getOpenList, getSidePadding }
}
