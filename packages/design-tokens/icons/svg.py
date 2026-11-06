# What kotlin.py and swift.py share: an icon's SVG element (path, circle or rect) as path data, and
# whether it is stroked, filled, or filled even-odd to cut holes.
import math, re

# The icons that point a way, turned round right to left.
MIRROR = {'arrow-left', 'arrow-right', 'chevron-left', 'chevron-right', 'sub-arrow', 'external', 'undo', 'backspace', 'volume', 'volume-off'}


def num(v):
    f = float(v)
    return ('%g' % f)


def to_path(el):
    filled = ('evenodd' if 'fill-rule="evenodd"' in el else True) if 'class="f"' in el else False
    tag = re.match(r'<(\w+)', el).group(1)
    a = dict(re.findall(r'([\w-]+)="([^"]*)"', el))
    if tag == 'path':
        d = a['d']
    elif tag == 'circle':
        cx, cy, r = float(a['cx']), float(a['cy']), float(a['r'])
        d = f'M{num(cx - r)} {num(cy)}a{num(r)} {num(r)} 0 1 0 {num(2 * r)} 0a{num(r)} {num(r)} 0 1 0 {num(-2 * r)} 0z'
    elif tag == 'rect':
        x, y, w, h = (float(a[k]) for k in ('x', 'y', 'width', 'height'))
        r = min(float(a.get('rx', 0)), w / 2, h / 2)
        if r == 0:
            d = f'M{num(x)} {num(y)}h{num(w)}v{num(h)}h{num(-w)}z'
        else:
            d = (f'M{num(x + r)} {num(y)}H{num(x + w - r)}A{num(r)} {num(r)} 0 0 1 {num(x + w)} {num(y + r)}'
                 f'V{num(y + h - r)}A{num(r)} {num(r)} 0 0 1 {num(x + w - r)} {num(y + h)}'
                 f'H{num(x + r)}A{num(r)} {num(r)} 0 0 1 {num(x)} {num(y + h - r)}'
                 f'V{num(y + r)}A{num(r)} {num(r)} 0 0 1 {num(x + r)} {num(y)}z')
    else:
        raise ValueError(tag)
    return d, filled


ARGS = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}


def tokens(d):
    """Path data as (command, numbers) pairs, a command's repeated arguments split into one each."""
    out = []
    for cmd, rest in re.findall(r'([MLHVCSQTAZmlhvcsqtaz])([^MLHVCSQTAZmlhvcsqtaz]*)', d):
        nums = [float(n) for n in re.findall(r'-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?', rest)]
        n = ARGS[cmd.upper()]
        if n == 0:
            out.append((cmd, []))
            continue
        if cmd.upper() == 'A':
            # The flags may run together with what follows them ("0 1 0 2 0" or "011 2").
            nums = arc_numbers(rest)
        for i in range(0, len(nums), n):
            # Further pairs after a move are lines.
            c = cmd if i == 0 or cmd.upper() != 'M' else ('l' if cmd == 'm' else 'L')
            out.append((c, nums[i:i + n]))
    return out


def arc_numbers(text):
    nums, i = [], 0
    pattern = re.compile(r'\s*,?\s*(-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?)')
    flag = re.compile(r'\s*,?\s*([01])')
    while True:
        k = len(nums) % 7
        m = (flag if k in (3, 4) else pattern).match(text, i)
        if not m:
            break
        nums.append(float(m.group(1)))
        i = m.end()
    return nums


def arc_to_cubics(x1, y1, rx, ry, phi, large, sweep, x2, y2):
    """An SVG arc as cubic Béziers, each of at most a quarter turn (the SVG spec's F.6)."""
    if (x1, y1) == (x2, y2):
        return []
    if rx == 0 or ry == 0:
        return [(x1, y1, x2, y2, x2, y2)]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(phi)
    cos, sin = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cos * dx + sin * dy, -sin * dx + cos * dy
    scale = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if scale > 1:
        rx, ry = rx * math.sqrt(scale), ry * math.sqrt(scale)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(0, num / den)) * (-1 if large == sweep else 1)
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx = cos * cxp - sin * cyp + (x1 + x2) / 2
    cy = sin * cxp + cos * cyp + (y1 + y2) / 2

    def angle(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    parts = max(1, math.ceil(abs(dt) / (math.pi / 2) - 1e-9))
    step = dt / parts
    k = 4 / 3 * math.tan(step / 4)
    out = []

    def point(t):
        x, y = rx * math.cos(t), ry * math.sin(t)
        return cos * x - sin * y + cx, sin * x + cos * y + cy

    def tangent(t):
        x, y = -rx * math.sin(t), ry * math.cos(t)
        return cos * x - sin * y, sin * x + cos * y

    for i in range(parts):
        a, b = t1 + i * step, t1 + (i + 1) * step
        (ax, ay), (bx, by) = point(a), point(b)
        (tax, tay), (tbx, tby) = tangent(a), tangent(b)
        out.append((ax + k * tax, ay + k * tay, bx - k * tbx, by - k * tby, bx, by))
    out[-1] = out[-1][:4] + (x2, y2)
    return out


def absolute(d):
    """Path data as absolute M, L, Q, C and Z commands alone, arcs as cubics."""
    out = []
    x = y = sx = sy = 0.0
    last_c = last_q = None
    for cmd, n in tokens(d):
        rel = cmd.islower()
        up = cmd.upper()
        ox, oy = (x, y) if rel else (0.0, 0.0)
        c_ctrl = q_ctrl = None
        if up == 'M':
            x, y = ox + n[0], oy + n[1]
            sx, sy = x, y
            out.append(('M', [x, y]))
        elif up == 'L':
            x, y = ox + n[0], oy + n[1]
            out.append(('L', [x, y]))
        elif up == 'H':
            x = (x if rel else 0) + n[0]
            out.append(('L', [x, y]))
        elif up == 'V':
            y = (y if rel else 0) + n[0]
            out.append(('L', [x, y]))
        elif up in ('C', 'S'):
            if up == 'C':
                c1 = (ox + n[0], oy + n[1])
                rest = n[2:]
            else:
                c1 = (2 * x - last_c[0], 2 * y - last_c[1]) if last_c else (x, y)
                rest = n
            c2 = (ox + rest[0], oy + rest[1])
            x, y = ox + rest[2], oy + rest[3]
            out.append(('C', [*c1, *c2, x, y]))
            c_ctrl = c2
        elif up in ('Q', 'T'):
            if up == 'Q':
                q = (ox + n[0], oy + n[1])
                rest = n[2:]
            else:
                q = (2 * x - last_q[0], 2 * y - last_q[1]) if last_q else (x, y)
                rest = n
            x, y = ox + rest[0], oy + rest[1]
            out.append(('Q', [*q, x, y]))
            q_ctrl = q
        elif up == 'A':
            ex, ey = ox + n[5], oy + n[6]
            for seg in arc_to_cubics(x, y, n[0], n[1], n[2], int(n[3]), int(n[4]), ex, ey):
                out.append(('C', list(seg)))
            x, y = ex, ey
        elif up == 'Z':
            x, y = sx, sy
            out.append(('Z', []))
        last_c, last_q = c_ctrl, q_ctrl
    return out


def compact(commands):
    """Absolute commands written out as path data, to three decimals."""
    def f(v):
        s = '%.3f' % v
        s = s.rstrip('0').rstrip('.')
        return '0' if s == '-0' else s
    return ''.join(c + ' '.join(f(v) for v in n) for c, n in commands)
