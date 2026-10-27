// The 3D car's sound (src/car-model-client.ts), all of it made in Web Audio rather than
// recorded. An electric motor's tones climb with its turning, louder and brighter while it
// pulls than once it's let go, when it gives back a thinner, lower whine as it slows; its low
// notes are given overtones, so a phone's speaker, which can't sound them, still carries them.
// The wind and the road's rumble run under it, soft as pink and brown noise are. As it sets off
// a soft two-note chime sounds, and a phone that can buzzes with its pull. The tyres sound as
// it's swung round, a whoosh as a press draws the camera in, the lamps' relay clicks, and it
// squeaks and settles as it brakes at its stop.
//
// Over it all a slow, endless tune is made up as it goes, a pad and a bass under a plucked
// line, which opens up as the car speeds off, though quieter, making way for the motor. All of
// it rings in a showroom's air, and the car's own sounds in a tunnel's once it's going, heard
// from the side its nose points to as it's swung round.
//
// It's off until the button by the car turns it on, and stays as it was left from page to page
// until the visit ends. A car out of sight, or a page in the background, is quiet, and once
// nothing's heard the sound stops altogether rather than going on silently.

/** Whether the sound is on, the same for every car. */
let on = false
let context: AudioContext | undefined
/** What follows the sound being turned on or off: each car, and each button. */
const followers = new Set<() => void>()
/** The cars heard now. */
const heard = new Set<object>()
let resting = 0

/** Turned on, which must be from a press for the browser to let it play. */
function setSound(next: boolean) {
  on = next
  if (on) context ??= new AudioContext()
  for (const follow of followers) follow()
  rest()
}

/** Running while a car is heard, and stopped once the last one has faded. */
function rest() {
  clearTimeout(resting)
  if (!context) return
  if (on && heard.size) {
    void context.resume()
  } else {
    resting = window.setTimeout(() => void context?.suspend(), FADE * 3000)
  }
}

/** The button turning the sound on and off, at the corner of the car's frame, which is then
 * no longer only a picture but a group holding it. It neither presses the car nor switches its
 * lamps. */
export function mountSoundToggle(frame: HTMLElement) {
  const still = frame.querySelector('.car-model__still')
  const toggle = document.createElement('button')
  const icon = document.createElement('span')
  toggle.type = 'button'
  toggle.className = 'car-model-sound'
  toggle.setAttribute('aria-label', '声音和音乐')
  toggle.append(icon)
  const show = () => {
    toggle.setAttribute('aria-pressed', String(on))
    toggle.title = on ? '关闭声音' : '打开声音'
    icon.className = on ? 'i-lucide-volume-2' : 'i-lucide-volume-x'
  }
  show()
  followers.add(show)
  toggle.addEventListener('click', (event) => {
    event.stopPropagation()
    setSound(!on)
  })
  toggle.addEventListener('pointerdown', (event) => event.stopPropagation())
  frame.setAttribute('role', 'group')
  still?.setAttribute('aria-hidden', 'true')
  frame.append(toggle)
  return () => {
    followers.delete(show)
    toggle.remove()
    frame.setAttribute('role', 'img')
    still?.removeAttribute('aria-hidden')
  }
}

/** How loud it all is, and how loud the tune is under the car. */
const VOLUME = 0.7
const MUSIC = 0.32
/** Seconds levels take to follow along, and to fade in and out. */
const FOLLOW = 0.05
const FADE = 0.4
/** How many times a second the motor turns at full speed (9,000 a minute), and how long
 * the speed takes to come up, by which its pull is told. */
const MOTOR_TOP = 150
const PULL = 0.7
/** How long the speed takes to fall away, by which how hard it's slowing is told; the whine it
 * gives back as it does, as a multiple of how fast it turns, and how loud. */
const COAST = 0.45
const REGEN_ORDER = 6.6
const REGEN = 0.035
/** How far to one side the car is heard with its nose pointing that way. */
const PAN = 0.6
/** The motor's tones, as multiples of how fast it turns, and how loud each is: a hum low
 * under a gearbox's whine and its overtone, the higher ones coming in as it speeds up. A
 * few cents apart where two would otherwise sound as one. */
const ORDERS = [
  { order: 2, gain: 0.5, detune: 0, rising: false },
  { order: 3, gain: 0.32, detune: 6, rising: false },
  { order: 4.6, gain: 0.22, detune: 0, rising: true },
  { order: 6, gain: 0.12, detune: -5, rising: true },
  { order: 9.2, gain: 0.05, detune: 0, rising: true },
]
/** How much of the car's sounds rings in the showroom, and in the tunnel at full speed, how
 * much of the tune rings, and how long each rings on for. */
const ROOM = 0.22
const TUNNEL = 0.5
const TUNE_ROOM = 0.18
const ROOM_SECONDS = 1.4
const TUNNEL_SECONDS = 3.2
/** How far the tune drops at full speed, making way for the motor. */
const DUCK = 0.6
/** How loud the overtones drawn from the low notes are, and how hard they're driven. */
const OVERTONES = 0.25
const DRIVE = 8
/** How fast it must be going, and pulling, for the chime as it sets off; and its two notes,
 * D and A, in the tune's key. */
const CHIME_AT = 0.05
const CHIME_PULL = 0.3
const CHIME = [74, 81]
/** How hard the car must pull for a phone to buzz, how often it does, in milliseconds, and how
 * long each buzz is at the least and at full speed. */
const BUZZ_PULL = 0.1
const BUZZ_EVERY = 140
const BUZZ = [8, 30]
/** The tune's beats a minute, and its bars of four, two to a chord. */
const TEMPO = 84
const STEP = 60 / TEMPO / 2
const STEPS_A_CHORD = 16
/** How far ahead the tune's notes are set down, and how often. */
const AHEAD = 0.25
const EVERY_MS = 60
/** Dm9, B♭maj7, Gm9, A: a root under the pad's four notes. */
const CHORDS = [
  { root: 38, pad: [53, 57, 60, 64] },
  { root: 34, pad: [50, 53, 57, 62] },
  { root: 31, pad: [50, 53, 57, 58] },
  { root: 33, pad: [52, 57, 61, 64] },
]
/** Which eighths of a chord's two bars the line plucks, and which of its notes. */
const PLUCKS = [0, 3, 6, 8, 10, 13]

export type CarState = {
  /** 0 parked to 1 at full speed. */
  speed: number
  /** The streaks rushing past, following the speed. */
  rush: number
  /** 0 to 1 while pressed, the wheels rolling. */
  roll: number
  /** How fast it's being swung round, in radians a second. */
  turn: number
  /** Where the camera is round it, in radians, 0 straight ahead of its nose. */
  azimuth: number
}

export function createCarSound() {
  let graph: ReturnType<typeof build> | undefined
  let audible = true
  const self = {}
  const isHeard = () => on && audible

  const follow = () => {
    if (isHeard()) heard.add(self)
    else heard.delete(self)
    if (on) graph ??= build(context!)
    if (!graph) return
    graph.out.gain.setTargetAtTime(isHeard() ? VOLUME : 0, context!.currentTime, FADE / 3)
    graph.music.play(isHeard())
    if (!isHeard()) graph.stopBuzz()
  }
  followers.add(follow)
  if (on) {
    follow()
    rest()
  }

  return {
    /** Out of sight, or the page in the background. */
    setAudible(now: boolean) {
      if (now === audible) return
      audible = now
      follow()
      rest()
    },
    update(state: CarState) {
      if (isHeard()) graph?.update(state)
    },
    press() {
      if (isHeard()) graph?.whoosh()
    },
    lamps(lit: boolean) {
      if (isHeard()) graph?.click(lit)
    },
    brake() {
      if (isHeard()) graph?.brake()
    },
    dispose() {
      followers.delete(follow)
      heard.delete(self)
      rest()
      graph?.dispose()
    },
  }
}

function build(context: AudioContext) {
  const sources: AudioScheduledSourceNode[] = []
  const at = (param: AudioParam, value: number) =>
    param.setTargetAtTime(value, context.currentTime, FOLLOW)

  const out = context.createGain()
  out.gain.value = 0
  const limit = context.createDynamicsCompressor()
  limit.threshold.value = -12
  out.connect(limit).connect(context.destination)
  // The car's own sounds, heard dry from the side its nose points to, and ringing all round in
  // the showroom, or the tunnel at speed.
  const car = context.createGain()
  const side = context.createStereoPanner()
  car.connect(side).connect(out)
  const room = context.createConvolver()
  room.buffer = createEcho(context, ROOM_SECONDS, 3, 0)
  room.connect(out)
  const tunnel = context.createConvolver()
  tunnel.buffer = createEcho(context, TUNNEL_SECONDS, 2, 0.6)
  tunnel.connect(out)
  const toRoom = context.createGain()
  toRoom.gain.value = ROOM
  const toTunnel = context.createGain()
  toTunnel.gain.value = 0
  car.connect(toRoom).connect(room)
  car.connect(toTunnel).connect(tunnel)

  // The motor: its tones in a soft filter, and a slow flutter through them as its pull ripples.
  const motorFilter = context.createBiquadFilter()
  motorFilter.type = 'lowpass'
  motorFilter.Q.value = 0.5
  const ripple = context.createGain()
  const motor = context.createGain()
  motor.gain.value = 0
  motorFilter.connect(ripple).connect(motor).connect(car)
  const flutter = oscillator(context, 'sine', 0)
  flutter.frequency.value = 7
  const flutterDepth = context.createGain()
  flutterDepth.gain.value = 0.08
  flutter.connect(flutterDepth).connect(ripple.gain)
  const tones = ORDERS.map(({ order, gain, detune, rising }) => {
    const tone = oscillator(context, 'sine', detune)
    const level = context.createGain()
    level.gain.value = rising ? 0 : gain
    tone.connect(level).connect(motorFilter)
    return { tone, level, order, gain, rising }
  })
  sources.push(flutter, ...tones.map(({ tone }) => tone))
  // Its notes below a phone speaker's reach, driven into overtones it can sound, which the ear
  // takes for the low note itself.
  const belowSpeaker = context.createBiquadFilter()
  belowSpeaker.type = 'lowpass'
  belowSpeaker.frequency.value = 160
  const drive = context.createGain()
  drive.gain.value = DRIVE
  const shaper = context.createWaveShaper()
  shaper.curve = saturation(4)
  const aboveLow = context.createBiquadFilter()
  aboveLow.type = 'highpass'
  aboveLow.frequency.value = 220
  const overtones = context.createGain()
  overtones.gain.value = OVERTONES
  motor.connect(belowSpeaker).connect(drive).connect(shaper).connect(aboveLow)
  aboveLow.connect(overtones).connect(side)
  // Slowing, it gives some back, a thinner whine a little below the rest, in a band of its own.
  const regen = oscillator(context, 'sine', -12)
  const regenBand = context.createBiquadFilter()
  regenBand.type = 'bandpass'
  regenBand.Q.value = 2
  const regenLevel = context.createGain()
  regenLevel.gain.value = 0
  regen.connect(regenBand).connect(regenLevel).connect(car)
  sources.push(regen)

  // Pink noise for the wind, high and rising; brown, deeper still, for the road low under it
  // and the tyres on the floor as it's swung round; white only for the lamps' sharp clicks.
  const white = createNoiseBuffer(context, 'white')
  const pink = loop(context, createNoiseBuffer(context, 'pink'))
  const brown = loop(context, createNoiseBuffer(context, 'brown'))
  const wind = filtered(context, pink, 'bandpass', 400, 0.5, car)
  const road = filtered(context, brown, 'lowpass', 140, 0.7, car)
  const tyres = filtered(context, brown, 'bandpass', 500, 1.4, car)
  sources.push(pink, brown)
  for (const source of sources) source.start()

  const music = createMusic(context, out, room)

  // How hard it's pulling or slowing, from how fast the speed comes up or falls away; whether
  // it has chimed since it last stood still; and when a phone last buzzed.
  let last = { speed: 0, time: context.currentTime }
  let chimed = false
  let buzzed = 0

  const update = ({ speed, rush, roll, turn, azimuth }: CarState) => {
    const time = context.currentTime
    const rate = time > last.time ? (speed - last.speed) / (time - last.time) : 0
    last = { speed, time }
    const pull = clamp(rate * PULL)
    const slowing = clamp(-rate * COAST)
    if (speed === 0) chimed = false
    if (!chimed && speed > CHIME_AT && pull > CHIME_PULL) {
      chimed = true
      chime()
    }
    if (pull > BUZZ_PULL) buzz(speed)
    else stopBuzz()

    const turning = MOTOR_TOP * speed ** 0.85 + 6 * roll
    const risen = Math.min(speed * 1.5, 1)
    for (const { tone, level, order, gain, rising } of tones) {
      at(tone.frequency, turning * order)
      if (rising) at(level.gain, gain * risen)
    }
    at(motorFilter.frequency, 400 + 1400 * speed * (0.5 + 0.5 * pull))
    at(motor.gain, 0.12 * risen * (0.4 + 0.6 * pull) + 0.02 * roll)
    at(regen.frequency, turning * REGEN_ORDER)
    at(regenBand.frequency, Math.max(turning * REGEN_ORDER, 40))
    at(regenLevel.gain, REGEN * slowing * speed)
    // The camera's right, seen from above, against the way the nose points.
    at(side.pan, -PAN * Math.sin(azimuth))
    at(toRoom.gain, ROOM * (1 - speed))
    at(toTunnel.gain, TUNNEL * speed)

    at(wind.filter.frequency, 400 + 900 * rush)
    at(wind.gain.gain, 0.05 * rush ** 1.5)
    at(road.gain.gain, 0.1 * speed + 0.03 * roll)
    const swung = Math.min(turn / 3, 1) * (1 - speed)
    at(tyres.filter.frequency, 380 + 500 * swung)
    at(tyres.gain.gain, 0.05 * swung)
    music.open(speed)
  }

  /** Two soft notes, the second higher, ringing on. */
  const chime = () => {
    const now = context.currentTime
    for (const [index, midi] of CHIME.entries()) {
      const time = now + index * 0.12
      const tone = context.createOscillator()
      tone.frequency.value = frequency(midi)
      tone.connect(envelope(context, time, 0.045, 0.01, 0.7)).connect(car)
      tone.start(time)
      tone.stop(time + 0.8)
    }
  }

  /** A short buzz now and then, longer the faster it's going. */
  const buzz = (speed: number) => {
    if (!('vibrate' in navigator)) return
    const now = performance.now()
    if (now - buzzed <= BUZZ_EVERY) return
    buzzed = now
    navigator.vibrate(Math.round(BUZZ[0]! + (BUZZ[1]! - BUZZ[0]!) * speed))
  }

  const stopBuzz = () => {
    if (buzzed && 'vibrate' in navigator) navigator.vibrate(0)
    buzzed = 0
  }

  /** Noise swept up through a band, as a press draws the camera in. */
  const whoosh = () => {
    const now = context.currentTime
    const sweep = shot(context, pink.buffer!, now, 0.6)
    const band = context.createBiquadFilter()
    band.type = 'bandpass'
    band.Q.value = 1.2
    band.frequency.setValueAtTime(260, now)
    band.frequency.exponentialRampToValueAtTime(1400, now + 0.45)
    const gain = envelope(context, now, 0.07, 0.15, 0.45)
    sweep.connect(band).connect(gain).connect(car)
  }

  /** A relay's two clicks, the second a beat later, and a short note, higher for on. */
  const click = (lit: boolean) => {
    const now = context.currentTime
    for (const [delay, loudness] of [
      [0, 0.5],
      [0.045, 0.25],
    ] as const) {
      const tick = shot(context, white, now + delay, 0.02)
      const high = context.createBiquadFilter()
      high.type = 'highpass'
      high.frequency.value = 2500
      tick
        .connect(high)
        .connect(envelope(context, now + delay, loudness, 0.001, 0.012))
        .connect(car)
    }
    const tone = context.createOscillator()
    tone.frequency.value = lit ? 1760 : 1175
    tone.connect(envelope(context, now + 0.02, 0.05, 0.004, 0.09)).connect(car)
    tone.start(now + 0.02)
    tone.stop(now + 0.15)
  }

  /** A short squeak of the tyres, and a thud as the springs take the weight. */
  const brake = () => {
    const now = context.currentTime
    const squeak = context.createOscillator()
    squeak.type = 'triangle'
    squeak.frequency.setValueAtTime(1150, now)
    squeak.frequency.exponentialRampToValueAtTime(820, now + 0.32)
    const vibrato = context.createOscillator()
    vibrato.frequency.value = 38
    const depth = context.createGain()
    depth.gain.value = 22
    vibrato.connect(depth).connect(squeak.frequency)
    squeak.connect(envelope(context, now, 0.035, 0.03, 0.3)).connect(car)
    const thud = context.createOscillator()
    thud.frequency.setValueAtTime(80, now + 0.12)
    thud.frequency.exponentialRampToValueAtTime(42, now + 0.4)
    thud.connect(envelope(context, now + 0.12, 0.35, 0.01, 0.3)).connect(car)
    for (const source of [squeak, vibrato, thud]) {
      source.start(now)
      source.stop(now + 0.6)
    }
  }

  return {
    out,
    music,
    update,
    whoosh,
    click,
    brake,
    stopBuzz,
    dispose() {
      stopBuzz()
      music.dispose()
      out.gain.setTargetAtTime(0, context.currentTime, 0.03)
      setTimeout(() => {
        for (const source of sources) source.stop()
        out.disconnect()
        limit.disconnect()
      }, 200)
    },
  }
}

/** The tune: it keeps its place when it's paused and goes on from there. */
function createMusic(context: AudioContext, out: AudioNode, room: AudioNode) {
  const bus = context.createGain()
  bus.gain.value = 0
  const tone = context.createBiquadFilter()
  tone.type = 'lowpass'
  tone.frequency.value = 2200
  bus.connect(tone).connect(out)
  const toRoom = context.createGain()
  toRoom.gain.value = TUNE_ROOM
  tone.connect(toRoom).connect(room)
  // The plucks ring on in a dotted eighth's echo.
  const echo = context.createDelay(1)
  echo.delayTime.value = STEP * 1.5
  const feedback = context.createGain()
  feedback.gain.value = 0.38
  const wet = context.createGain()
  wet.gain.value = 0.4
  echo.connect(feedback).connect(echo)
  echo.connect(wet).connect(bus)

  let step = 0
  let next = 0
  let timer = 0
  const schedule = () => {
    while (next < context.currentTime + AHEAD) {
      play(step, next)
      step = (step + 1) % (STEPS_A_CHORD * CHORDS.length)
      next += STEP
    }
  }

  const play = (step: number, time: number) => {
    const chord = CHORDS[Math.floor(step / STEPS_A_CHORD)]!
    const within = step % STEPS_A_CHORD
    const length = STEP * STEPS_A_CHORD
    if (within === 0) {
      for (const note of chord.pad) pad(note, time, length)
    }
    if (within % 4 === 0) note('triangle', chord.root, time, STEP * 3, within === 0 ? 0.2 : 0.12)
    const pluck = PLUCKS.indexOf(within)
    if (pluck >= 0) {
      const at = chord.pad[(pluck * 3 + Math.floor(step / STEPS_A_CHORD)) % chord.pad.length]!
      note('triangle', at + 12, time, STEP * 0.9, 0.06, echo)
    }
  }

  /** Two saws a little apart, swelling in and out through a soft filter. */
  const pad = (midi: number, time: number, length: number) => {
    const filter = context.createBiquadFilter()
    filter.type = 'lowpass'
    filter.frequency.setValueAtTime(500, time)
    filter.frequency.linearRampToValueAtTime(1100, time + length / 2)
    filter.frequency.linearRampToValueAtTime(500, time + length + 1.5)
    const gain = context.createGain()
    gain.gain.setValueAtTime(0, time)
    gain.gain.linearRampToValueAtTime(0.022, time + 1.6)
    gain.gain.setValueAtTime(0.022, time + length)
    gain.gain.linearRampToValueAtTime(0, time + length + 1.5)
    filter.connect(gain).connect(bus)
    for (const detune of [-8, 8]) {
      const voice = context.createOscillator()
      voice.type = 'sawtooth'
      voice.frequency.value = frequency(midi)
      voice.detune.value = detune
      voice.connect(filter)
      voice.start(time)
      voice.stop(time + length + 1.6)
    }
  }

  const note = (
    type: OscillatorType,
    midi: number,
    time: number,
    length: number,
    loudness: number,
    send?: AudioNode,
  ) => {
    const voice = context.createOscillator()
    voice.type = type
    voice.frequency.value = frequency(midi)
    const gain = envelope(context, time, loudness, 0.01, length)
    voice.connect(gain).connect(bus)
    if (send) gain.connect(send)
    voice.start(time)
    voice.stop(time + length + 0.05)
  }

  return {
    play(now: boolean) {
      bus.gain.setTargetAtTime(now ? MUSIC : 0, context.currentTime, FADE)
      if (now && !timer) {
        next = Math.max(next, context.currentTime + 0.05)
        schedule()
        timer = window.setInterval(schedule, EVERY_MS)
      } else if (!now && timer) {
        clearInterval(timer)
        timer = 0
      }
    },
    /** A little brighter but quieter as the car speeds off, back as it slows. */
    open(speed: number) {
      tone.frequency.setTargetAtTime(2200 + 1500 * speed, context.currentTime, FOLLOW)
      if (timer) bus.gain.setTargetAtTime(MUSIC * (1 - DUCK * speed), context.currentTime, FOLLOW)
    },
    dispose() {
      clearInterval(timer)
      timer = 0
    },
  }
}

function clamp(value: number) {
  return Math.min(Math.max(value, 0), 1)
}

function frequency(midi: number) {
  return 440 * 2 ** ((midi - 69) / 12)
}

function oscillator(context: AudioContext, type: OscillatorType, detune: number) {
  const node = context.createOscillator()
  node.type = type
  node.detune.value = detune
  return node
}

/** Two seconds of noise: white, even across the pitches; pink, falling away up them as wind
 * does (Paul Kellet's filter); or brown, deeper still, a random walk. */
function createNoiseBuffer(context: AudioContext, color: 'white' | 'pink' | 'brown') {
  const buffer = context.createBuffer(1, context.sampleRate * 2, context.sampleRate)
  const samples = buffer.getChannelData(0)
  let [b0, b1, b2, b3, b4, b5, b6] = [0, 0, 0, 0, 0, 0, 0]
  let walk = 0
  for (let index = 0; index < samples.length; index++) {
    const white = Math.random() * 2 - 1
    if (color === 'white') {
      samples[index] = white
    } else if (color === 'pink') {
      b0 = 0.99886 * b0 + white * 0.0555179
      b1 = 0.99332 * b1 + white * 0.0750759
      b2 = 0.969 * b2 + white * 0.153852
      b3 = 0.8665 * b3 + white * 0.3104856
      b4 = 0.55 * b4 + white * 0.5329522
      b5 = -0.7616 * b5 - white * 0.016898
      samples[index] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11
      b6 = white * 0.115926
    } else {
      walk = (walk + 0.02 * white) / 1.02
      samples[index] = walk * 3.5
    }
  }
  return buffer
}

function loop(context: AudioContext, buffer: AudioBuffer) {
  const source = context.createBufferSource()
  source.buffer = buffer
  source.loop = true
  return source
}

/** How a space rings: noise in both ears dying away over `seconds`, falling off by `power`,
 * and the higher pitches dying sooner by `dark`, as they do in a long tunnel. */
function createEcho(context: AudioContext, seconds: number, power: number, dark: number) {
  const length = Math.round(context.sampleRate * seconds)
  const buffer = context.createBuffer(2, length, context.sampleRate)
  for (let channel = 0; channel < 2; channel++) {
    const samples = buffer.getChannelData(channel)
    let smooth = 0
    for (let index = 0; index < length; index++) {
      const left = 1 - index / length
      const white = Math.random() * 2 - 1
      // Smoothed more the later it is, so the ring darkens as it dies.
      const keep = dark * (1 - left)
      smooth = keep * smooth + (1 - keep) * white
      samples[index] = smooth * left ** power
    }
  }
  return buffer
}

function filtered(
  context: AudioContext,
  source: AudioNode,
  type: BiquadFilterType,
  frequency: number,
  q: number,
  out: AudioNode,
) {
  const filter = context.createBiquadFilter()
  filter.type = type
  filter.frequency.value = frequency
  filter.Q.value = q
  const gain = context.createGain()
  gain.gain.value = 0
  source.connect(filter).connect(gain).connect(out)
  return { filter, gain }
}

/** A stretch of the noise, played once from a random place in it. */
function shot(context: AudioContext, buffer: AudioBuffer, time: number, length: number) {
  const source = context.createBufferSource()
  source.buffer = buffer
  source.start(time, Math.random() * (buffer.duration - length), length)
  return source
}

/** A soft clipping curve, rounder the lower `hardness` is. */
function saturation(hardness: number) {
  const curve = new Float32Array(1025)
  for (let index = 0; index < curve.length; index++) {
    const x = (index / (curve.length - 1)) * 2 - 1
    curve[index] = Math.tanh(hardness * x) / Math.tanh(hardness)
  }
  return curve
}

/** Up to `peak` in `attack` seconds, and away again over `decay`. */
function envelope(
  context: AudioContext,
  time: number,
  peak: number,
  attack: number,
  decay: number,
) {
  const gain = context.createGain()
  gain.gain.setValueAtTime(0, time)
  gain.gain.linearRampToValueAtTime(peak, time + attack)
  gain.gain.exponentialRampToValueAtTime(0.0001, time + attack + decay)
  return gain
}
