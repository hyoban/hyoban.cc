// Recording the trip's stage to a video: capturing this tab, cropped to the stage,
// and encoding it with the music. Chromium-only in practice (Region Capture).

// avc1 keeps the codec setup in the file header; QuickTime can't play avc3 MP4s.
const VIDEO_TYPES = [
  'video/mp4;codecs=avc1,mp4a.40.2',
  'video/mp4',
  'video/webm;codecs=vp9,opus',
  'video/webm',
]
const VIDEO_BITS_PER_SECOND = 12_000_000
// How long to wait for the first captured frame before recording anyway.
const FIRST_FRAME_TIMEOUT = 1000

// Region Capture is not in the DOM typings yet.
type CroppableTrack = MediaStreamTrack & { cropTo?: (target: unknown) => Promise<void> }
type CropTargetConstructor = { fromElement: (element: Element) => Promise<unknown> }

export type Recording = ReturnType<typeof createRecording>

export function canRecord() {
  return 'getDisplayMedia' in (navigator.mediaDevices ?? {}) && typeof MediaRecorder !== 'undefined'
}

/**
 * Asks to capture this tab, or returns nothing when that's refused. It needs the
 * click that started it, so it has to come before anything else is awaited.
 */
export async function requestTabCapture() {
  try {
    return await navigator.mediaDevices.getDisplayMedia({
      audio: false,
      preferCurrentTab: true,
      selfBrowserSurface: 'include',
      video: { cursor: 'never', displaySurface: 'browser', frameRate: 30 },
    } as DisplayMediaStreamOptions)
  } catch {
    return undefined
  }
}

/** Crops the capture to `element`; without Region Capture it keeps the whole tab. */
export async function cropTo(stream: MediaStream, element: Element) {
  const track = stream.getVideoTracks()[0] as CroppableTrack | undefined
  const cropTarget = (window as Window & { CropTarget?: CropTargetConstructor }).CropTarget

  try {
    if (track?.cropTo && cropTarget) {
      await track.cropTo(await cropTarget.fromElement(element))
    }
  } catch {
    // The whole tab, then.
  }
}

export function releaseCapture(stream: MediaStream) {
  for (const track of stream.getTracks()) {
    track.stop()
  }
}

/**
 * Records the capture at `element`'s size in device pixels, with `audio` mixed in.
 * Nothing is recorded until `start`; after `stop`, `onStop` gets the video and the
 * file extension it suits.
 */
export function createRecording(
  stream: MediaStream,
  element: Element,
  audio: readonly MediaStreamTrack[],
  onStop: (video: Blob, extension: string) => void,
) {
  const [source] = stream.getVideoTracks()
  const { height, width } = element.getBoundingClientRect()
  // Even sizes, which the encoder needs.
  const even = (size: number) => Math.round((size * devicePixelRatio) / 2) * 2
  const steady = source ? createSteadyTrack(source, even(width), even(height)) : undefined
  const mimeType = VIDEO_TYPES.find((type) => MediaRecorder.isTypeSupported(type))
  const recorder = new MediaRecorder(
    new MediaStream([...(steady?.track ? [steady.track] : []), ...audio]),
    { ...(mimeType ? { mimeType } : {}), videoBitsPerSecond: VIDEO_BITS_PER_SECOND },
  )
  const chunks: Blob[] = []

  recorder.addEventListener('dataavailable', (event) => {
    if (event.data.size > 0) {
      chunks.push(event.data)
    }
  })
  recorder.addEventListener('stop', () => {
    const type = recorder.mimeType || 'video/webm'
    onStop(new Blob(chunks, { type }), type.includes('mp4') ? 'mp4' : 'webm')
  })

  return {
    /** Settles once the first frame is in, or after a moment without one. */
    ready: Promise.race([
      steady?.ready,
      new Promise((resolve) => setTimeout(resolve, FIRST_FRAME_TIMEOUT)),
    ]),
    get active() {
      return recorder.state === 'recording'
    },
    start() {
      recorder.start(1000)
    },
    stop() {
      recorder.stop()
    },
    /** Lets go of the capture and the canvas it is drawn onto. */
    release() {
      steady?.stop()
      releaseCapture(stream)
    },
  }
}

/**
 * Redraws a captured video track onto a canvas of a fixed size. A tab capture can
 * change size mid-recording (a crop settling, the window or zoom changing), and an
 * avc1 MP4 only records the first size, so later frames decode as garbage.
 */
function createSteadyTrack(source: MediaStreamTrack, width: number, height: number) {
  const video = document.createElement('video')
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d')
  const [track] = canvas.captureStream(30).getVideoTracks()
  let stopped = false
  let markReady: () => void
  const ready = new Promise<void>((resolve) => (markReady = resolve))

  canvas.width = width
  canvas.height = height
  video.muted = true
  video.playsInline = true
  video.srcObject = new MediaStream([source])

  const draw = () => {
    if (stopped) {
      return
    }

    if (video.videoWidth > 0) {
      context?.drawImage(video, 0, 0, width, height)
      markReady()
    }

    video.requestVideoFrameCallback(draw)
  }

  video.requestVideoFrameCallback(draw)
  void video.play().catch(() => {})

  return {
    ready,
    stop() {
      stopped = true
      video.srcObject = null
      track?.stop()
    },
    track,
  }
}
