# Writes the Apple apps' HereKit/Generated/AppIcons.swift from icons.py
# (python3 packages/design-tokens/icons/swift.py): each icon its stroked, filled and even-odd filled
# paths, made absolute M, L, Q, C and Z commands alone (arcs, circles and rects as curves), which
# AppIcon.swift reads into SwiftUI paths on the 24 grid.
import importlib.util, os

from svg import MIRROR, absolute, compact, to_path

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('icons', os.path.join(HERE, 'icons.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def camel(name):
    head, *rest = name.split('-')
    return head + ''.join(p.capitalize() for p in rest)


def strings(ds):
    return '[' + ', '.join(f'"{compact(absolute(d))}"' for d in ds) + ']'


out = ['''// Generated from packages/design-tokens/icons by `python3 packages/design-tokens/icons/swift.py`. Do not edit by hand.

// The app's own icons, as the design system draws them (its Icons group): a 24 grid, lines
// %g wide with round ends and joins, a few filled marks, drawn in the colour of the text around
// them. icons.py there is the design system's source too.

extension AppIcon {
    /// The width of every icon's lines on its 24 grid.
    public static let stroke: Double = %g
''' % (m.STROKE, m.STROKE)]
for group, icons in m.GROUPS.items():
    out.append(f'    // {group}')
    for name, els in icons.items():
        paths = [to_path(e) for e in els]
        strokes = [d for d, f in paths if not f]
        fills = [d for d, f in paths if f is True]
        holed = [d for d, f in paths if f == 'evenodd']
        args = [f'"{name}"', f'mirror: {"true" if name in MIRROR else "false"}', f'strokes: {strings(strokes)}']
        if fills:
            args.append(f'fills: {strings(fills)}')
        if holed:
            args.append(f'holed: {strings(holed)}')
        out.append(f'    public static let {camel(name)} = AppIcon({", ".join(args)})')
    out.append('')
names = [camel(name) for icons in m.GROUPS.values() for name in icons]
out.append('    /// Every icon, in the design system\'s order.')
out.append('    public static let all: [AppIcon] = [' + ', '.join(names) + ']')
out.append('}')
path = os.path.join(HERE, '../../../apps/apple/Sources/HereKit/Generated/AppIcons.swift')
os.makedirs(os.path.dirname(path), exist_ok=True)
open(path, 'w').write('\n'.join(out) + '\n')
print('wrote', sum(len(v) for v in m.GROUPS.values()))
