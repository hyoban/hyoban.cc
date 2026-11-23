# @hyoban/drawer

[Base UI](https://base-ui.com/react/components/drawer)'s Drawer without React: a popup
opened and closed from script, swiped away (or between snap points) by mouse, pen or touch,
and nestable. It writes Base UI's state onto the elements as the same data attributes and CSS
variables. [styles.css](src/styles.css) is [shadcn/ui](https://ui.shadcn.com/docs/components/drawer)'s
Drawer (base-nova) as plain CSS over them.

```html
<div data-drawer-backdrop hidden></div>
<aside id="filters" hidden aria-labelledby="filters-title">
  <div data-drawer-handle></div>
  <div data-drawer-content>
    <h2 id="filters-title">Filters</h2>
    <button data-drawer-close>Done</button>
  </div>
</aside>
```

```ts
import '@hyoban/drawer/styles.css'
import { createDrawer } from '@hyoban/drawer'

const drawer = createDrawer({
  popup: document.querySelector('#filters')!,
  backdrop: document.querySelector('[data-drawer-backdrop]'),
  triggers: document.querySelectorAll('[data-open-filters]'),
  swipeDirection: 'down',
  snapPoints: [0.5, 1],
})
```

- **Popup**: a `<dialog>` opens with `showModal()` while `modal` (the default); any other
  element toggles `hidden`. A mouse swipes from anywhere but `data-drawer-content`; a finger
  from anywhere, though a scroller in the way scrolls first, until its edge.
  `data-swipe-ignore` excludes an element (`="x"` on a vertical drawer lets it take only
  sideways drags).
- **Closing**: Escape, a press outside, a `data-drawer-close` button or a swipe, each through
  `onOpenChange(open, details)`; `details.cancel()` keeps it open.
- **Snap points** (up or down only): a number up to 1 is a share of the viewport height,
  above 1 is pixels, or a `'px'` / `'rem'` string. `data-expanded` is set at 1.
- **Nesting**: `parent` (a drawer, or a function called on open) steps that drawer back while
  this one is open, like shadcn's stack.
- **Scroll area**: `createScrollArea({ viewport, root })` hides an element's scrollbar and
  draws shadcn's over its edge inside `root` (e.g. a drawer's popup), with a draggable thumb,
  a pressable track and wheel scrolling over it. Thumb colour: `--scroll-area-thumb`.

## The port

Ported from `@base-ui/react` 1.9.0 and shadcn/ui's base-nova Drawer, as of October 2026.
Each source file says at its top which upstream files it comes from.

| Here                 | Upstream                                                                                                                                                               |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/drawer.ts`      | Base UI `drawer/root/DrawerRoot`, `drawer/popup/DrawerPopup`, `drawer/backdrop/DrawerBackdrop`, and the release and snap logic of `drawer/viewport/DrawerViewport`     |
| `src/swipe.ts`       | Base UI `utils/useSwipeDismiss`, `utils/scrollable`, and the touch handling of `drawer/viewport/DrawerViewport`                                                        |
| `src/scroll-area.ts` | Base UI `scroll-area/root/ScrollAreaRoot`, `scroll-area/viewport/ScrollAreaViewport`, `scroll-area/scrollbar/ScrollAreaScrollbar`, `scroll-area/thumb/ScrollAreaThumb` |
| `src/snap-points.ts` | Base UI `drawer/root/useDrawerSnapPoints`                                                                                                                              |
| `src/styles.css`     | shadcn/ui `styles/base-nova/drawer` and `styles/base-nova/scroll-area` (their Tailwind classes, written out as CSS)                                                    |

Differences from upstream:

- One function over DOM elements instead of React parts and context. Parts are the elements
  passed in (`data-drawer-*` marks them for the stylesheet); nesting is the `parent` option.
- A drag moves the popup only through CSS variables, never an inline `transform`.
- Opening and closing use `<dialog>` (`showModal()`, its `cancel`) or `hidden`, not Floating
  UI's focus manager and portals.
- Colours come from `--drawer-*` variables instead of shadcn's theme. Styles sit in
  `@layer drawer` and `:where()`, so any page rule overrides them.
- Left out: `Drawer.SwipeArea` (swiping one open from the edge), `Drawer.Indent`, the
  virtual keyboard handling, and focus trapping for anything but a modal `<dialog>`.

## License

MIT, see [LICENSE](LICENSE). Base UI (Copyright (c) 2019 Material-UI SAS) and shadcn/ui
(Copyright (c) 2023 shadcn) are MIT too. Their notices are in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and the source files, and must accompany any
copy of this code, bundles included.
