# A sample bundle

What the recording app exports, with `local` stripped: a manifest, the places it
knows, a trip, and three Moments. `media/` holds the originals of media that isn't
published yet, named `<media uid>.<extension>`; the publish test adds one for the first
Moment and leaves the third one's missing, so the test covers both.

- `nanjing-2` sits 50 m from the repository's `nanjing`, so publishing renames it.
- `yantai-port` is new.
- The first Moment gets its slug from its first place, the second proposes `ferry-morning`
  itself, the third is skipped because its photo isn't in the bundle.
