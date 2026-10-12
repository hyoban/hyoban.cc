import { closeMusic, fadeMusicIn, loadMusic, type Music } from './music.ts'

// Background music for the ordinary card playback, switched on with the music
// button: the chosen track plays while the cards play and pauses with them.

export function createAmbientMusic({
  getSource,
  isPlaying,
  isWanted,
}: {
  /** The track to play, a URL; empty for none. */
  getSource: () => string
  /** Whether the cards are playing right now. */
  isPlaying: () => boolean
  /** Whether music should be playing at all: switched on, a trip open, no stage running. */
  isWanted: () => boolean
}) {
  let ambient: (Music & { src: string }) | undefined
  let pending: { src: string } | undefined

  /** Plays the chosen track while the card playback runs, and pauses it otherwise. */
  function sync() {
    const src = getSource()

    // Switched off, no trip, or the stage has its own music: release it entirely.
    if (!isWanted() || (ambient && ambient.src !== src)) {
      stop()
    }

    if (!isWanted()) {
      return
    }

    // Paused playback only pauses the music, so it picks up where it was. A hidden tab
    // holds the card too (see the player's tick), so the music waits with it.
    if (!isPlaying()) {
      void ambient?.context.suspend()
      return
    }

    if (ambient) {
      void ambient.context.resume()
      return
    }

    const loading = { src }
    pending = loading

    void loadMusic(src, { audible: true, record: false }).then((music) => {
      if (!music || pending !== loading) {
        void music?.context.close()
        return
      }

      pending = undefined
      ambient = { ...music, src }
      fadeMusicIn(music)
      sync()
    })
  }

  function stop() {
    pending = undefined

    if (ambient) {
      closeMusic(ambient)
      ambient = undefined
    }
  }

  return { stop, sync }
}
