import {
  AdditiveBlending,
  Color,
  DoubleSide,
  InstancedBufferAttribute,
  InstancedBufferGeometry,
  Mesh,
  PlaneGeometry,
  ShaderMaterial,
  Vector2,
  Vector3,
} from 'three'
import { Reflector } from 'three/examples/jsm/objects/Reflector.js'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'

// The streaks and the floor the 3D car (src/car-model-client.ts) speeds off over when it's
// held down.

/** Long light streaks, mostly pale white, lilac and blue, on a tunnel along the length of a
 * car of `size`, round whoever's watching, `follow`ing the camera so they spread evenly all
 * round the view, from beyond the car however the camera (`distance` from it) stands, out
 * to a few times that, so they pass round it rather than through it. Those below the ground
 * are hidden where the floor, `floorRadius` across and fading out, covers them, so that near
 * the car only their reflections show. A few are bright and most faint, together a haze.
 * They drift back past the car with `advance`, longer the faster it goes, each softening
 * towards its ends, and fade out at the tunnel's. One set is drawn with the car; the other,
 * the same streaks, lights its paint. */
export function createTunnel(distance: number, size: Vector3, floorRadius: number) {
  const count = 640
  const span = distance * 16
  // Each streak a pair of crossed ribbons along its length, so that it shows its width from
  // any side, soft across it so it glows.
  const ribbon = new PlaneGeometry(1, 1).rotateX(-Math.PI / 2)
  const crossed = mergeGeometries([ribbon, ribbon.clone().rotateZ(Math.PI / 2)])!
  const geometry = new InstancedBufferGeometry()
  geometry.index = crossed.index
  geometry.setAttribute('position', crossed.getAttribute('position'))
  geometry.setAttribute('uv', crossed.getAttribute('uv'))
  geometry.instanceCount = count
  const streaks = new Float32Array(count * 4)
  const colors = new Float32Array(count * 3)
  // Pale ones, white and lilac twice as often, and fewer in stronger colours.
  const palette = [
    '#ffffff',
    '#ffffff',
    '#e4dcff',
    '#e4dcff',
    '#c7d2fe',
    '#bfdbfe',
    '#d8b4fe',
    '#f0abfc',
    '#a78bfa',
    '#a5f3fc',
  ]
  const color = new Color()
  for (let index = 0; index < count; index++) {
    const radius =
      distance + Math.hypot(size.x, size.z) * 0.6 + distance * Math.random() ** 1.6 * 2.6
    streaks.set(
      [Math.random() * Math.PI * 2, radius, Math.random(), size.z * (1.5 + Math.random() * 5)],
      index * 4,
    )
    color.set(palette[Math.floor(Math.random() * palette.length)]!)
    color.multiplyScalar(0.12 + Math.random() ** 3 * 1.9)
    colors.set([color.r, color.g, color.b], index * 3)
  }
  geometry.setAttribute('streak', new InstancedBufferAttribute(streaks, 4))
  geometry.setAttribute('tint', new InstancedBufferAttribute(colors, 3))
  const material = new ShaderMaterial({
    uniforms: {
      travel: { value: 0 },
      speed: { value: 0 },
      glow: { value: 0 },
      span: { value: span },
      axis: { value: new Vector2(0, size.y * 0.4) },
      breadth: { value: 1 },
      brightness: { value: 1 },
      pace: { value: 1 },
      lengthen: { value: 1 },
      floorRadius: { value: floorRadius },
      floorCover: { value: 0 },
    },
    vertexShader: /* glsl */ `
      attribute vec4 streak;
      attribute vec3 tint;
      uniform float travel;
      uniform float speed;
      uniform float span;
      uniform vec2 axis;
      uniform float breadth;
      uniform float pace;
      uniform float lengthen;
      varying vec3 vTint;
      varying float vFade;
      varying float vAlong;
      varying float vAcross;
      varying vec3 vSpot;
      void main() {
        float z = mod(streak.z * span - travel * pace, span) - span * 0.5;
        float stretch = streak.w * (0.3 + speed) * lengthen;
        float thickness = streak.y * 0.012 * breadth;
        // Flattened to about the view's own shape, so from the side as many fall above the
        // car, within the view's height, as below it.
        vec3 at = vec3(axis + vec2(cos(streak.x), sin(streak.x) * 0.45) * streak.y, z);
        vec3 local = vec3(position.xy * thickness, position.z * stretch);
        vTint = tint;
        vAcross = uv.x * 2.0 - 1.0;
        vAlong = position.z + 0.5;
        vSpot = at + local;
        // Gone at the tunnel's ends.
        vFade = 1.0 - smoothstep(0.3, 0.5, abs(z) / span);
        gl_Position = projectionMatrix * modelViewMatrix * vec4(at + local, 1.0);
      }
    `,
    fragmentShader: /* glsl */ `
      uniform float glow;
      uniform float brightness;
      uniform float floorRadius;
      uniform float floorCover;
      varying vec3 vTint;
      varying float vFade;
      varying float vAlong;
      varying float vAcross;
      varying vec3 vSpot;
      void main() {
        // Under the floor, near the car where the floor is plainest, it is hidden.
        float covered = floorRadius > 0.0 && vSpot.y < 0.0
          ? floorCover * (1.0 - smoothstep(0.05, 0.5, length(vSpot.xz) / floorRadius))
          : 0.0;
        // A bright core in a soft halo, brightest a little ahead of its middle and softening
        // to its ends. The sine is kept from dipping below nothing at the very ends, where
        // its power would be no number at all and, blurred into the paint's light, would
        // black out the whole car for a frame.
        float across = vAcross * vAcross;
        float profile = exp(-across * 40.0) + 0.35 * exp(-across * 5.0) * (1.0 - across);
        float lengthwise = pow(max(sin(3.14159 * vAlong), 0.0), 1.5) * (0.5 + 0.5 * vAlong);
        gl_FragColor = vec4(
          vTint * profile * lengthwise * vFade * glow * brightness * (1.0 - covered),
          1.0
        );
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }
    `,
    blending: AdditiveBlending,
    transparent: true,
    depthWrite: false,
    // Seen from either side of the car, so from either side of its ribbons.
    side: DoubleSide,
  })
  // Those the paint reflects are broad, dimmer, three times as long and a quarter as fast,
  // so their light flows calmly over its polish rather than flashing across it.
  const reflected = material.clone()
  // The paint sees the streaks round itself, from among them, with no floor in the way.
  reflected.uniforms = {
    ...material.uniforms,
    axis: { value: new Vector2(0, size.y * 0.4) },
    breadth: { value: 12 },
    brightness: { value: 1.2 },
    pace: { value: 0.25 },
    lengthen: { value: 3 },
    floorRadius: { value: 0 },
  }
  const drawn = new Mesh(geometry, material)
  const reflection = new Mesh(geometry, reflected)
  for (const mesh of [drawn, reflection]) {
    mesh.frustumCulled = false
    mesh.renderOrder = 1
  }
  return {
    streaks: drawn,
    reflection,
    /** Keep the tunnel round the camera as it moves. */
    follow(camera: Vector3) {
      material.uniforms.axis!.value.set(camera.x, camera.y)
    },
    /** How fast the car goes, which is also how much the floor's there, and how brightly the
     * streaks show. */
    advance(speed: number, glow: number, delta: number) {
      material.uniforms.speed!.value = speed
      material.uniforms.glow!.value = glow
      material.uniforms.floorCover!.value = speed
      material.uniforms.travel!.value += speed * span * 0.9 * delta
      drawn.visible = reflection.visible = glow > 0
    },
  }
}

/** A glossy black floor reaching out `size` across, mirroring the car and the streaks dimly
 * and a little blurred, as lacquer would rather than glass, and fading out towards its edges
 * into the dark. */
export function createFloor(size: number) {
  const mesh = new Reflector(new PlaneGeometry(size, size), {
    color: 0x8c8c8c,
    multisample: 0,
    shader: {
      name: 'CarFloor',
      uniforms: {
        color: { value: null },
        tDiffuse: { value: null },
        textureMatrix: { value: null },
        opacity: { value: 0 },
      },
      vertexShader: /* glsl */ `
        uniform mat4 textureMatrix;
        varying vec4 vMirror;
        varying vec2 vSpot;
        void main() {
          vMirror = textureMatrix * vec4(position, 1.0);
          vSpot = position.xy;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }
      `,
      fragmentShader: /* glsl */ `
        uniform vec3 color;
        uniform sampler2D tDiffuse;
        uniform float opacity;
        varying vec4 vMirror;
        varying vec2 vSpot;
        void main() {
          float spread = length(vSpot) / ${(size / 2).toFixed(3)};
          float fade = 1.0 - smoothstep(0.08, 1.0, spread);
          // Smeared up and down the screen, as reflections in a wet road are.
          vec2 at = vMirror.xy / vMirror.w;
          vec3 mirrored = vec3(0.0);
          float weights = 0.0;
          for (int tap = -6; tap <= 6; tap++) {
            float offset = float(tap);
            float weight = exp(-offset * offset / 18.0);
            mirrored += texture2D(tDiffuse, at + vec2(offset * 0.0008, offset * 0.0035)).rgb
              * weight;
            weights += weight;
          }
          mirrored /= weights;
          gl_FragColor = vec4(mirrored * color, opacity * fade);
          #include <tonemapping_fragment>
          #include <colorspace_fragment>
        }
      `,
    },
  })
  mesh.rotation.x = -Math.PI / 2
  mesh.visible = false
  // Under the car's shadow.
  mesh.renderOrder = -1
  const material = mesh.material as ShaderMaterial
  material.transparent = true
  material.depthWrite = false
  return {
    mesh,
    set opacity(value: number) {
      material.uniforms.opacity!.value = value
    },
    /** Mirror at half the canvas's resolution. */
    fit(drawn: Vector2) {
      mesh.getRenderTarget().setSize(Math.ceil(drawn.x / 2), Math.ceil(drawn.y / 2))
    },
  }
}

export function smoothstep(from: number, to: number, value: number) {
  const t = Math.min(Math.max((value - from) / (to - from), 0), 1)
  return t * t * (3 - 2 * t)
}
