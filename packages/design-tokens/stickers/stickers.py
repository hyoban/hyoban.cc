# The stickers 打码 lays over a face, drawn in the icons' hand (icons.py) but in colour: 24 grid,
# round caps and joins, a 1.5 ink line, and a white die-cut edge round the whole sticker so it reads
# on any photo. Each is a list of layers, back to front; a layer is an SVG element with its own fill
# and stroke. A sticker covers a patch whole: the app draws it a little larger than the patch, so
# its round edge still takes in the patch's corners.

import re

INK = '#2b2620'
EDGE = '#ffffff'
LINE = 1.5
# The die-cut edge, stroked under the silhouette.
CUT = 3.5


def el(tag, attrs, fill='none', stroke=None, width=LINE):
    s = f' stroke="{stroke}" stroke-width="{width}"' if stroke else ''
    a = ' '.join(f'{k}="{v}"' for k, v in attrs.items())
    return f'<{tag} {a} fill="{fill}"{s}/>'


def path(d, fill='none', stroke=INK, width=LINE):
    return el('path', {'d': d}, fill, stroke, width)


def circle(cx, cy, r, fill='none', stroke=INK, width=LINE):
    return el('circle', {'cx': cx, 'cy': cy, 'r': r}, fill, stroke, width)


def ellipse(cx, cy, rx, ry, fill='none', stroke=INK, width=LINE):
    return el('ellipse', {'cx': cx, 'cy': cy, 'rx': rx, 'ry': ry}, fill, stroke, width)


def dot(cx, cy, r=1.1, fill=INK):
    return el('circle', {'cx': cx, 'cy': cy, 'r': r}, fill)


def cut(shapes):
    """The silhouette's white die-cut edge: each outer shape again, white and stroked wide, behind it."""
    return [re.sub(r'fill="[^"]*"', f'fill="{EDGE}"', s, count=1)
            .replace(f'stroke="{INK}" stroke-width="{LINE}"', f'stroke="{EDGE}" stroke-width="{CUT}"') for s in shapes]


CAT_HEAD = 'M4.6 10.2 5.1 5.2Q5.25 3.9 6.3 4.6L9.6 7.2Q12 6.5 14.4 7.2L17.7 4.6Q18.75 3.9 18.9 5.2L19.4 10.2C20.4 11.6 20.6 13 20.6 13.6 20.6 17.6 16.8 20.2 12 20.2S3.4 17.6 3.4 13.6C3.4 13 3.6 11.6 4.6 10.2z'


def cat():
    head = path(CAT_HEAD, '#f0a64b')
    return cut([head]) + [
        head,
        path('M6.2 6.4 6.6 9 8.4 7.6z', '#ffd6a0', None),
        path('M17.8 6.4 17.4 9 15.6 7.6z', '#ffd6a0', None),
        ellipse(12, 16.4, 4.2, 2.9, '#ffe7c4', None),
        dot(8.9, 13.2, 1.2), dot(15.1, 13.2, 1.2),
        path('M11 15h2l-1 1.1z', INK, INK, 1),
        path('M12 16.1v.8M10.6 17.6c.7.5 2.1.5 2.8 0'),
        path('M3 14.2l2.6.4M3.2 16.4l2.5-.5M21 14.2l-2.6.4M20.8 16.4l-2.5-.5'),
        ellipse(7, 15.6, 1.2, .8, '#f28f7a', None), ellipse(17, 15.6, 1.2, .8, '#f28f7a', None),
    ]


def dog():
    head = ellipse(12, 13, 7.2, 7.4, '#e3c49e')
    left = path('M6.2 6.2C3.6 6.6 2.6 11.4 3.4 14.6c.4 1.5 2.4 1.5 2.9 0L7.9 9.2c.5-1.6-.4-3.2-1.7-3z', '#9a6a45')
    right = path('M17.8 6.2c2.6.4 3.6 5.2 2.8 8.4-.4 1.5-2.4 1.5-2.9 0L16.1 9.2c-.5-1.6.4-3.2 1.7-3z', '#9a6a45')
    return cut([head, left, right]) + [
        head, left, right,
        ellipse(12, 16.2, 3.8, 2.8, '#fbeedd', None),
        circle(15.6, 10.8, 1.9, '#b98a62', None),
        dot(9, 12, 1.2), dot(15, 12, 1.2),
        ellipse(12, 14.6, 1.5, 1.1, INK, None),
        path('M12 15.7v1M10.5 17.2c.8.6 2.2.6 3 0'),
        path('M12.4 18.1c.1.9.5 1.3 1 1.3s.8-.6.6-1.5', '#f28f7a', INK, 1),
    ]


def bear():
    ears = [circle(6.4, 6.6, 2.9, '#9b6a43'), circle(17.6, 6.6, 2.9, '#9b6a43')]
    head = circle(12, 13, 7.8, '#9b6a43')
    return cut(ears + [head]) + ears + [
        circle(6.4, 6.6, 1.4, '#d8ab82', None), circle(17.6, 6.6, 1.4, '#d8ab82', None),
        head,
        ellipse(12, 15.8, 3.6, 2.7, '#e8c9a6', None),
        dot(8.8, 11.8, 1.2), dot(15.2, 11.8, 1.2),
        ellipse(12, 14.5, 1.4, 1, INK, None),
        path('M12 15.5v.8M10.7 16.9c.7.5 1.9.5 2.6 0'),
    ]


def rabbit():
    ears = [path('M8.8 9.4C7.2 6.6 7 2.9 8.5 2.3s3 2.6 3 6.5', '#f7f3ee'),
            path('M15.2 9.4c1.6-2.8 1.8-6.5.3-7.1s-3 2.6-3 6.5', '#f7f3ee')]
    head = ellipse(12, 14.6, 7.6, 6.6, '#f7f3ee')
    return cut(ears + [head]) + ears + [
        path('M9.3 8.2C8.4 6.4 8.3 4.3 8.8 3.9s1.6 1.6 1.7 3.9', '#f4b3c2', None),
        path('M14.7 8.2c.9-1.8 1-3.9.5-4.3s-1.6 1.6-1.7 3.9', '#f4b3c2', None),
        head,
        dot(9, 13.8, 1.2), dot(15, 13.8, 1.2),
        path('M11.1 15.6h1.8l-.9 1z', '#f28f7a', INK, 1),
        path('M12 16.6v.6M10.7 17.9c.7.5 1.9.5 2.6 0'),
        ellipse(7.4, 16.4, 1.2, .8, '#f4b3c2', None), ellipse(16.6, 16.4, 1.2, .8, '#f4b3c2', None),
    ]


def smile():
    face = circle(12, 12, 9.2, '#f6c945')
    return cut([face]) + [
        face,
        path('M7.4 10.6c.6-1 2-1 2.6 0M14 10.6c.6-1 2-1 2.6 0'),
        path('M7.8 13.6a4.6 4.6 0 0 0 8.4 0z', '#ffffff'),
        ellipse(6.4, 13.4, 1.3, .9, '#f08a6c', None), ellipse(17.6, 13.4, 1.3, .9, '#f08a6c', None),
    ]


def flower():
    petals = [circle(12 + 5.6 * x, 12 + 5.6 * y, 3.9, '#f28fb0') for x, y in
              [(0, -1), (.866, -.5), (.866, .5), (0, 1), (-.866, .5), (-.866, -.5)]]
    centre = circle(12, 12, 4.6, '#f6c945')
    return cut(petals) + petals + [
        centre,
        dot(10.4, 11.4, .9), dot(13.6, 11.4, .9),
        path('M10.4 13.2c.9.8 2.3.8 3.2 0'),
    ]


STICKERS = {'cat': cat(), 'dog': dog(), 'bear': bear(), 'rabbit': rabbit(), 'smile': smile(), 'flower': flower()}


# A license plate's patch is long and low: its stickers are long animals on a 48 by 18 grid, in the
# same hand, each lying along the plate so its body covers it end to end.

def lying_cat():
    tail = path('M38.6 13.2c3.6.6 6.2-1 6.8-3.9.2-1.1 1.8-1.1 1.9.1.3 4-3 6.6-8.3 6.2z', '#f0a64b')
    body = path('M10 5h24.6c4.6 0 7.8 3.4 7.8 6.6 0 2.8-1.6 5-3.8 5H10z', '#f0a64b')
    head = path('M3.4 8.4 3.8 3.4Q4 2.2 5 2.9L7.8 5Q10 4.4 12.2 5L15 2.9Q16 2.2 16.2 3.4L16.6 8.4C17.4 9.6 17.6 10.8 17.6 11.4 17.6 14.8 14.4 16.8 10 16.8S2.4 14.8 2.4 11.4C2.4 10.8 2.6 9.6 3.4 8.4z', '#f0a64b')
    return cut([tail, body, head]) + [
        tail, body,
        path('M24 5.4v3.6M28.4 5.4v4M32.8 5.6v3.6', 'none', '#c97a2a'),
        ellipse(21, 16.4, 2.2, 1.1, '#ffe7c4', INK, 1), ellipse(26.4, 16.4, 2.2, 1.1, '#ffe7c4', INK, 1),
        head,
        path('M4.8 4.6 5.1 6.8 6.6 5.6z', '#ffd6a0', None), path('M15.2 4.6 14.9 6.8 13.4 5.6z', '#ffd6a0', None),
        ellipse(10, 13.6, 3.6, 2.4, '#ffe7c4', None),
        path('M6.6 10.4c.5.6 1.3.6 1.8 0M11.6 10.4c.5.6 1.3.6 1.8 0'),
        path('M9.2 12.4h1.6l-.8.9z', INK, INK, 1),
        path('M10 13.3v.6M8.9 14.6c.6.4 1.6.4 2.2 0'),
        ellipse(5.2, 12.8, 1.1, .7, '#f28f7a', None), ellipse(14.8, 12.8, 1.1, .7, '#f28f7a', None),
    ]


def dachshund():
    tail = path('M7.6 8.6C5.4 7.6 3.6 5.6 3.4 3.4c-.1-1.1 1.3-1.4 1.8-.4 1 2 2.4 3.3 4 3.9z', '#b8743f')
    legs = [path(f'M{x} 13v3.4c0 .9.6 1.4 1.4 1.4h1.2c.8 0 1.4-.5 1.4-1.4V13z', '#b8743f') for x in (8.4, 13.6, 27.4, 32.6)]
    body = path('M10.4 4.4h21.4c3.6 0 6.2 2.6 6.2 5.4s-2.6 5.4-6.2 5.4H10.4c-3.4 0-5.6-2.4-5.6-5.4s2.2-5.4 5.6-5.4z', '#b8743f')
    head = path('M35 3.8c2.6-2 6.6-1.6 8.4.8l2.6 3.2c1.4 1.7.4 4-1.8 4.2l-4.4.4c-2.6.2-5.2-1.2-5.8-3.6-.4-1.8-.2-3.8 1-5z', '#b8743f')
    return cut([tail, body, head] + legs) + [tail] + legs + [
        body,
        ellipse(21, 12.8, 7, 1.8, '#e7b98c', None),
        head,
        path('M36.2 4.6c-1.8.4-2.8 2.6-2.4 5.2.2 1.2 1.6 1.4 2.2.4l1.4-3c.6-1.4-.2-2.8-1.2-2.6z', '#7e4a26'),
        dot(40.6, 6.2, 1.1),
        ellipse(45.4, 8.6, 1.2, .9, INK, None),
        path('M41.4 10.6c.9.6 2 .6 2.8.1'),
    ]


def caterpillar():
    segments = [circle(x, 10 + (.4 if i % 2 else -.4), 6.2, '#8cc86a') for i, x in enumerate((6.6, 13, 19.4, 25.8, 32.2))]
    head = circle(40, 8.8, 6, '#a6d77f')
    antennae = path('M38 3 36.8 1M42 3l1.2-2')
    return cut(segments + [head]) + segments + [
        dot(6.6 + 6.4 * i, 16.6 + (.4 if i % 2 else -.4), .9, '#4f8a3a') for i in range(5)
    ] + [
        path('M11.4 7.4c.7-.7 1.8-.7 2.6 0M24.2 7.4c.7-.7 1.8-.7 2.6 0', 'none', '#5d9c45'),
        head, antennae, dot(36.8, 1, .9), dot(43.2, 1, .9),
        dot(38, 8.4, 1.1), dot(42, 8.4, 1.1),
        path('M38.6 11c.8.7 2 .7 2.8 0'),
        ellipse(36.6, 10.6, 1.1, .7, '#f28f7a', None), ellipse(43.4, 10.6, 1.1, .7, '#f28f7a', None),
    ]


def fish():
    tail = path('M9 9 3.4 4.4c-.8-.6-1.8 0-1.6 1L2.8 9 1.8 12.6c-.2 1 .8 1.6 1.6 1L9 9z', '#5aa3d8')
    body = path('M7.4 9C11.6 3.6 22 1.8 31.4 2.6c7.6.6 12.8 3.6 13.4 6.4-.6 2.8-5.8 5.8-13.4 6.4C22 16.2 11.6 14.4 7.4 9z', '#7cc0ec')
    return cut([tail, body]) + [
        tail, body,
        path('M24 5.4c1.6 1.8 1.6 5.4 0 7.2M28.6 5c1.8 2 1.8 6 0 8', 'none', '#4b8fc2'),
        path('M33.4 10.4c3 1.2 6.4 1 9 0', '#cfe9fb', None),
        dot(39.4, 7.4, 1.1),
        path('M42.2 10.2c.6.5 1.4.5 2 0'),
        ellipse(36.6, 9.6, 1.1, .7, '#f28f7a', None),
    ]


PLATE_STICKERS = {'lying_cat': lying_cat(), 'dachshund': dachshund(), 'caterpillar': caterpillar(), 'fish': fish()}
PLATE_WIDTH, PLATE_HEIGHT = 48, 18

# Where each sticker is solid, as left, top, width and height on its grid: the app draws a sticker so
# that this box takes in the whole patch, centred on it. A face sticker's is a square 1.25 times
# smaller than its grid, so its round face takes in the patch's corners; a plate sticker's is its body.
FACE_COVER = (1.6, 1.6, 20.8, 20.8)
COVERS = {
    'lying_cat': (5, 5.6, 31, 10.4),
    'dachshund': (8, 5.4, 28, 8.8),
    'caterpillar': (4, 5.6, 30, 8.6),
    'fish': (14, 4.4, 24, 9.2),
}


def svg(layers, size=24, width=24, height=24):
    body = ''.join(layers)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="-1 -1 {width + 2} {height + 2}" '
            f'width="{size * (width + 2) / 26:g}" height="{size * (height + 2) / 26:g}" '
            f'stroke-linecap="round" stroke-linejoin="round">{body}</svg>')


if __name__ == '__main__':
    # A page of every sticker on a photo's grey, to look at them by: python3 stickers.py > preview.html
    tiles = ''.join(f'<figure>{svg(v, 88)}<figcaption>{k}</figcaption></figure>' for k, v in STICKERS.items())
    tiles += ''.join(f'<figure style="grid-column:span 3">{svg(v, 88, PLATE_WIDTH, PLATE_HEIGHT)}<figcaption>{k}</figcaption></figure>'
                     for k, v in PLATE_STICKERS.items())
    print('<!doctype html><meta charset="utf-8"><style>figure{margin:0;text-align:center}</style>'
          '<body style="margin:0;padding:24px;display:grid;grid-template-columns:repeat(3,120px);align-items:end;gap:16px;'
          f'background:#6b7a86;font:13px system-ui;color:#fff">{tiles}</body>')
