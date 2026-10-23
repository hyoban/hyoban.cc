# @hyoban/drawer

[Base UI](https://base-ui.com/react/components/drawer)'s Drawer without React: one popup
opened and closed from script, swiped away (or between snap points) with a mouse, a pen
or a finger, nested over another drawer, with the state Base UI renders written onto the
elements as the same data attributes and CSS variables. [styles.css](src/styles.css) is
[shadcn/ui](https://ui.shadcn.com/docs/components/drawer)'s Drawer (base-nova) as plain
CSS over them.

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

- **Popup**: a `<dialog>` is shown with `showModal()` while `modal` (the default), and any
  other element by its `hidden`. It's swiped from anywhere but `data-drawer-content` with a
  mouse, and from anywhere with a finger: a scroller in the way scrolls first, until its
  edge. `data-swipe-ignore` keeps an element out of it (`="x"` on a vertical drawer hands
  it only sideways drags).
- **Closing**: Escape, a press outside, a `data-drawer-close` button or a swipe, each
  through `onOpenChange(open, details)`, where `details.cancel()` keeps it open.
- **Snap points** (up or down only): a number up to 1 is a share of the viewport's height,
  above 1 pixels, or a `'px'` / `'rem'` string. `data-expanded` at 1.
- **Nesting**: `parent` (a drawer, or a function asked as it opens) steps that drawer back
  while this one is open, as shadcn's stack.
- **Scroll area**: `createScrollArea({ viewport, root })` hides an element's own scrollbar
  and draws shadcn's over its edge in `root` (a drawer's popup, say), the thumb dragged,
  the track pressed, a wheel over it scrolling. The thumb's colour is `--scroll-area-thumb`.

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

What changed on the way:

- One function on DOM elements instead of React parts and context: the parts are found by
  the elements passed in (`data-drawer-*` marks them for the stylesheet), and nesting is
  the `parent` option instead of rendering one Root in another.
- A drag moves the popup through the CSS variables alone, never an inline `transform`.
- Opening and closing are the `<dialog>` element's own (`showModal()`, its `cancel`) or
  the `hidden` attribute, instead of Floating UI's focus manager and portals.
- The look reads colours from `--drawer-*` variables instead of shadcn's theme, and sits
  in `@layer drawer` and `:where()` so a page overrides it without trying.
- Left out: `Drawer.SwipeArea` (swiping one open from the edge), `Drawer.Indent`, the
  virtual keyboard handling, and focus trapping for anything but a modal `<dialog>`.

## License

MIT, see [LICENSE](LICENSE). Base UI (Copyright (c) 2019 Material-UI SAS) and shadcn/ui
(Copyright (c) 2023 shadcn) are MIT too; their notices are in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and the source files, and go with any copy
of this code, a bundle of it included.
