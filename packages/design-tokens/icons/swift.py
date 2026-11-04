# Writes the iOS app's icons from icons.py (python3 packages/design-tokens/icons/swift.py): each icon an
# SVG image set in Here/Assets.xcassets/Icons, drawn in black with every element's paint written out
# (the asset catalog's SVG reader takes no CSS), kept as vectors and rendered as a template, so each
# takes the colour of the text around it. Those that point a way turn round right to left.
import importlib.util, json, os, re, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('icons', os.path.join(HERE, 'icons.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

MIRROR = {'arrow-left', 'arrow-right', 'chevron-left', 'chevron-right', 'sub-arrow', 'external', 'undo', 'backspace', 'volume', 'volume-off'}

STROKED = f'fill="none" stroke="#000" stroke-width="{m.STROKE:g}" stroke-linecap="round" stroke-linejoin="round"'
FILLED = 'fill="#000" stroke="none"'


def paint(element):
    """An element with its own paint in place of the set's class."""
    if 'class="f"' in element:
        return element.replace(' class="f"', ' ' + FILLED)
    return re.sub(r'^<(\w+)', lambda tag: f'<{tag.group(1)} {STROKED}', element)


def svg(elements):
    body = ''.join(paint(e) for e in elements)
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">{body}</svg>\n'


root = os.path.join(HERE, '../../../apps/ios/Here/Assets.xcassets/Icons')
shutil.rmtree(root, ignore_errors=True)
os.makedirs(root)
info = {'author': 'xcode', 'version': 1}
with open(os.path.join(root, 'Contents.json'), 'w') as f:
    json.dump({'info': info, 'properties': {'provides-namespace': False}}, f, indent=2)
    f.write('\n')

count = 0
for icons in m.GROUPS.values():
    for name, elements in icons.items():
        directory = os.path.join(root, f'{name}.imageset')
        os.makedirs(directory)
        with open(os.path.join(directory, f'{name}.svg'), 'w') as f:
            f.write(svg(elements))
        image = {'filename': f'{name}.svg', 'idiom': 'universal'}
        if name in MIRROR:
            image['language-direction'] = 'left-to-right'
        contents = {
            'images': [image],
            'info': info,
            'properties': {'preserves-vector-representation': True, 'template-rendering-intent': 'template'},
        }
        with open(os.path.join(directory, 'Contents.json'), 'w') as f:
            json.dump(contents, f, indent=2)
            f.write('\n')
        count += 1
print('wrote', count)
