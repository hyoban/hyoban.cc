import { h, OG_COLORS as colors, type OgNode } from '@/og-layout'

export type OgPanel =
  | {
      kind: 'calendar'
      year: number
      month: number
      days: { day: number; active: boolean; photo?: string | undefined }[]
    }
  | { kind: 'posters'; photos: string[] }
  | { kind: 'months'; months: { photo?: string | undefined }[] }
  | { kind: 'trips'; trips: { title: string; detail: string; photo?: string | undefined }[] }
  | { kind: 'route'; route: string; photo?: string | undefined }

type LoadImage = (
  url: string,
  width: number,
  height: number,
  fit?: 'cover' | 'contain',
) => Promise<string>
const OG_PANEL_WIDTH = 660

/** Text in a panel must be included in the font subsets too. */
export function getPanelText(panel?: OgPanel) {
  if (panel?.kind === 'calendar') return '一二三四五六日0123456789'
  if (panel?.kind === 'trips') return panel.trips.map((trip) => trip.title + trip.detail).join('')
  return ''
}

function picture(src: string, left: number, top: number, width: number, height: number) {
  return h('img', { position: 'absolute', left, top, width, height, borderRadius: 12 }, undefined, {
    src,
    width,
    height,
  })
}

export async function renderOgPanel(panel: OgPanel, load: LoadImage) {
  const children: OgNode[] = []
  if (panel.kind === 'calendar') {
    const first = (new Date(Date.UTC(panel.year, panel.month - 1, 1)).getUTCDay() + 6) % 7
    const rows = Math.ceil((first + panel.days.length) / 7)
    const top = (630 - (rows * 80 - 8 + 44)) / 2
    children.push(
      ...Array.from('一二三四五六日', (label, column) =>
        h(
          'div',
          {
            position: 'absolute',
            left: 26 + column * 88,
            top,
            width: 80,
            justifyContent: 'center',
            fontSize: 24,
            color: colors.tx2,
          },
          label,
        ),
      ),
    )
    const cells = await Promise.all(
      panel.days.map(async (day) => {
        const slot = first + day.day - 1
        const left = 26 + (slot % 7) * 88
        const y = top + 44 + Math.floor(slot / 7) * 80
        const image = day.photo ? await load(day.photo, 80, 72) : undefined
        return h(
          'div',
          {
            position: 'absolute',
            left,
            top: y,
            width: 80,
            height: 72,
            display: 'flex',
            borderRadius: 10,
            overflow: 'hidden',
            backgroundColor: day.active ? '#e8eee8' : '#f2f2ef',
          },
          [
            image && picture(image, 0, 0, 80, 72),
            h(
              'div',
              {
                position: 'absolute',
                left: 6,
                top: 5,
                width: 34,
                height: 34,
                borderRadius: 8,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 26,
                fontWeight: 500,
                color: colors.tx,
                backgroundColor: image ? '#fdfdfce8' : 'transparent',
              },
              String(day.day),
            ),
            !image &&
              day.active &&
              h('div', {
                position: 'absolute',
                right: 10,
                bottom: 10,
                width: 8,
                height: 8,
                borderRadius: 4,
                backgroundColor: '#608267',
              }),
          ].filter(Boolean),
        )
      }),
    )
    children.push(...cells)
  } else if (panel.kind === 'months') {
    // A year's twelve months, four to a row, each its fullest Moment's photo alone; a month
    // without one keeps its place, empty.
    const width = 147
    const height = 190
    const gap = 8
    children.push(
      ...(await Promise.all(
        panel.months.map(async (month, index) => {
          const left = 24 + (index % 4) * (width + gap)
          const top = 24 + Math.floor(index / 4) * (height + gap)
          if (month.photo)
            return picture(await load(month.photo, width, height), left, top, width, height)
          return h('div', {
            position: 'absolute',
            left,
            top,
            width,
            height,
            borderRadius: 12,
            backgroundColor: '#f2f2ef',
          })
        }),
      )),
    )
  } else if (panel.kind === 'posters') {
    const photos = panel.photos.slice(0, 6)
    const columns = Math.min(3, photos.length)
    const rows = Math.ceil(photos.length / columns)
    children.push(
      ...(await Promise.all(
        photos.map(async (url, index) =>
          picture(
            await load(url, 180, 270, 'contain'),
            (660 - columns * 180 - (columns - 1) * 12) / 2 + (index % columns) * 192,
            (630 - rows * 270 - (rows - 1) * 12) / 2 + Math.floor(index / columns) * 282,
            180,
            270,
          ),
        ),
      )),
    )
  } else if (panel.kind === 'trips') {
    const trips = panel.trips.slice(0, 3)
    // One trip has the panel to itself, its photo above its words; more share it in rows,
    // their photos as large as the rows let them be.
    const single = trips.length === 1
    const rowHeight = single ? 531 : trips.length === 2 ? 240 : 164
    const gap = trips.length === 2 ? 24 : 20
    const photo = single
      ? { width: 612, height: 420 }
      : { width: Math.round(rowHeight * 1.27), height: rowHeight }
    const top = (630 - trips.length * rowHeight - (trips.length - 1) * gap) / 2
    children.push(
      ...(await Promise.all(
        trips.map(async (trip, index) => {
          const image = trip.photo ? await load(trip.photo, photo.width, photo.height) : undefined
          return h(
            'div',
            {
              position: 'absolute',
              left: 24,
              top: top + index * (rowHeight + gap),
              width: 612,
              height: rowHeight,
              display: 'flex',
              flexDirection: single ? 'column' : 'row',
              alignItems: single ? 'stretch' : 'center',
              gap: single ? 20 : 24,
            },
            [
              image
                ? h('img', { ...photo, borderRadius: 12, flexShrink: 0 }, undefined, {
                    src: image,
                    ...photo,
                  })
                : h(
                    'div',
                    {
                      ...photo,
                      display: 'flex',
                      borderRadius: 12,
                      flexShrink: 0,
                      backgroundColor: colors.border,
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: 56,
                      color: colors.tx3,
                    },
                    String(index + 1).padStart(2, '0'),
                  ),
              h(
                'div',
                {
                  display: 'flex',
                  flex: 1,
                  minWidth: 0,
                  flexDirection: 'column',
                  gap: single ? 8 : 12,
                },
                [
                  h(
                    'div',
                    {
                      display: 'block',
                      fontSize: single || trips.length === 2 ? 36 : 34,
                      lineHeight: 1.3,
                      fontWeight: 500,
                      lineClamp: single ? 1 : 2,
                      color: colors.tx,
                    },
                    trip.title,
                  ),
                  h(
                    'div',
                    { display: 'block', fontSize: 26, lineHeight: 1.4, color: colors.tx2 },
                    trip.detail,
                  ),
                ],
              ),
            ],
          )
        }),
      )),
    )
  } else {
    children.push(picture(panel.route, 24, panel.photo ? 16 : 154, 612, 321))
    if (panel.photo) children.push(picture(await load(panel.photo, 612, 252), 24, 354, 612, 252))
  }
  return h(
    'div',
    { position: 'relative', display: 'flex', flexShrink: 0, width: OG_PANEL_WIDTH, height: 630 },
    children,
  )
}
