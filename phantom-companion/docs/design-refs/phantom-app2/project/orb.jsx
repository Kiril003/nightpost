/* orb.jsx — PHANTOM Familiar visual.
   Three render tiers (selected by tweak):
     - 'compose'      : pure CSS layered radial (cheap, always works)
     - 'rendereffect' : Canvas2D with bloom + audio-reactive scaling
     - 'filament'     : WebGL fragment shader — soft sphere with
                        FBM membrane wobble + subsurface-scattering vibe.
   All tiers expose the same props so any caller can swap freely. */

const ORB_STATE_LOOK = {
  // [coreA, coreB, halo, brightness, motion, opacity, wobble, spikes, drift]
  FOCUS:    { core: '#FFD370', mid: '#F4AF25', halo: '#7A4818', bright: 1.0,  motion: 1.0, opacity: 1.0,  wobble: 0.18, spikes: 0,    drift: 0    },
  DIALOGUE: { core: '#FFE08A', mid: '#F8B83A', halo: '#8C5418', bright: 1.15, motion: 1.1, opacity: 1.0,  wobble: 0.32, spikes: 0,    drift: 0    },
  SENTINEL: { core: '#FFC766', mid: '#F49A20', halo: '#7A3812', bright: 1.10, motion: 1.3, opacity: 1.0,  wobble: 0.22, spikes: 1,    drift: 0    },
  GHOST:    { core: '#5a5a5a', mid: '#2a2a2a', halo: '#0a0a0a', bright: 0.20, motion: 0.6, opacity: 0.22, wobble: 0.06, spikes: 0,    drift: 0    },
  SHADOW:   { core: '#A07A40', mid: '#6A4818', halo: '#1A100A', bright: 0.40, motion: 0.4, opacity: 0.72, wobble: 0.08, spikes: 0,    drift: 0    },
  DREAM:    { core: '#FFD9A5', mid: '#B07A40', halo: '#2A1810', bright: 0.45, motion: 0.3, opacity: 0.65, wobble: 0.22, spikes: 0,    drift: 0.85 },
};

// ── easings ──
const lerp = (a, b, t) => a + (b - a) * t;
function springStep(cur, target, dt, stiffness = 6) {
  const k = 1 - Math.exp(-stiffness * dt);
  return cur + (target - cur) * k;
}

// ── Common: spec/look smoothing across state changes ──
function useSmoothedLook(state, motionScale) {
  const targetRef = React.useRef(ORB_STATE_LOOK[state]);
  const curRef = React.useRef({ ...ORB_STATE_LOOK[state] });
  const lastT = React.useRef(performance.now());
  const [, force] = React.useReducer((x) => x + 1, 0);

  React.useEffect(() => { targetRef.current = ORB_STATE_LOOK[state]; }, [state]);

  React.useEffect(() => {
    let raf;
    const tick = () => {
      const now = performance.now();
      const dt = Math.min(0.05, (now - lastT.current) / 1000);
      lastT.current = now;
      const t = targetRef.current, c = curRef.current;
      // smooth scalar fields
      ['bright', 'motion', 'opacity', 'wobble', 'spikes', 'drift'].forEach((k) => {
        c[k] = springStep(c[k], t[k], dt, 4);
      });
      // colors snap (but we crossfade visually via translucent halos)
      c.core = t.core; c.mid = t.mid; c.halo = t.halo;
      force();
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  return { ...curRef.current, motion: curRef.current.motion * motionScale };
}

// ─────────────────────────────────────────────────────────────────────────────
// CSS tier — pure stacked radial gradients, breathing keyframes.
// Never touches GPU beyond compositing.
// ─────────────────────────────────────────────────────────────────────────────
function OrbCSS({ size = 200, look, audio = 0, breathPeriod = 8 }) {
  const scale = 1 + audio * 0.12;
  const period = breathPeriod / Math.max(0.2, look.motion);
  return (
    <div style={{
      width: size, height: size, position: 'relative',
      opacity: look.opacity,
      transition: 'opacity 1.2s ease',
    }}>
      <div style={{
        position: 'absolute', inset: '-30%',
        background: `radial-gradient(circle at 50% 55%,
          ${look.halo} 0%, transparent 60%)`,
        filter: 'blur(20px)',
        animation: `phantom-breathe ${period}s ease-in-out infinite`,
        opacity: 0.6 * look.bright,
      }} />
      <div style={{
        position: 'absolute', inset: 0,
        background: `radial-gradient(circle at 45% 40%,
          ${look.core} 0%,
          ${look.mid} 28%,
          ${look.halo} 55%,
          transparent 75%)`,
        filter: `blur(${size * 0.02}px) brightness(${look.bright})`,
        transform: `scale(${scale})`,
        transition: 'transform 0.08s linear',
        borderRadius: '50%',
      }} />
      <div style={{
        position: 'absolute', inset: '15%',
        background: `radial-gradient(circle at 38% 32%,
          rgba(255,255,255,0.9) 0%,
          ${look.core} 18%,
          transparent 60%)`,
        mixBlendMode: 'screen',
        opacity: 0.7 * look.bright,
        borderRadius: '50%',
        animation: `phantom-pulse-glow ${period * 0.6}s ease-in-out infinite`,
      }} />
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Canvas2D tier — radial bloom + soft particles + audio-reactive ring.
// ─────────────────────────────────────────────────────────────────────────────
function OrbCanvas({ size = 200, look, audio = 0, breathPeriod = 8 }) {
  const ref = React.useRef(null);
  const lookRef = React.useRef(look);  lookRef.current = look;
  const audioRef = React.useRef(audio); audioRef.current = audio;
  const breathRef = React.useRef(breathPeriod); breathRef.current = breathPeriod;
  const startRef = React.useRef(performance.now());

  React.useEffect(() => {
    const cnv = ref.current; if (!cnv) return;
    const ctx = cnv.getContext('2d');
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    cnv.width = size * dpr; cnv.height = size * dpr;
    cnv.style.width = size + 'px'; cnv.style.height = size + 'px';

    let raf;
    const tick = () => {
      const t = (performance.now() - startRef.current) / 1000;
      const L = lookRef.current;
      const a = audioRef.current;
      const breath = 0.5 + 0.5 * Math.sin((t * 2 * Math.PI) / (breathRef.current / Math.max(0.2, L.motion)));
      const cx = size * dpr / 2;
      const cy = size * dpr / 2;
      const baseR = size * dpr * 0.35 * (1 + a * 0.15 + breath * 0.04);

      ctx.clearRect(0, 0, cnv.width, cnv.height);
      ctx.globalAlpha = L.opacity;

      // halo
      const halo = ctx.createRadialGradient(cx, cy, 0, cx, cy, baseR * 2.4);
      halo.addColorStop(0, L.halo + '00');
      halo.addColorStop(0.3, L.halo + '88');
      halo.addColorStop(0.6, L.halo + '44');
      halo.addColorStop(1, L.halo + '00');
      ctx.fillStyle = halo;
      ctx.fillRect(0, 0, cnv.width, cnv.height);

      // body
      ctx.globalCompositeOperation = 'screen';
      const body = ctx.createRadialGradient(
        cx - baseR * 0.15, cy - baseR * 0.2, 0,
        cx, cy, baseR
      );
      body.addColorStop(0, L.core);
      body.addColorStop(0.4, L.mid);
      body.addColorStop(0.85, L.halo);
      body.addColorStop(1, L.halo + '00');
      ctx.fillStyle = body;
      ctx.beginPath(); ctx.arc(cx, cy, baseR, 0, Math.PI * 2); ctx.fill();

      // specular
      ctx.globalCompositeOperation = 'lighter';
      const spec = ctx.createRadialGradient(
        cx - baseR * 0.3, cy - baseR * 0.35, 0,
        cx - baseR * 0.3, cy - baseR * 0.35, baseR * 0.5
      );
      spec.addColorStop(0, `rgba(255,255,255,${0.7 * L.bright})`);
      spec.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = spec;
      ctx.beginPath(); ctx.arc(cx - baseR * 0.3, cy - baseR * 0.35, baseR * 0.5, 0, Math.PI * 2); ctx.fill();

      // anomaly spikes (sentinel only)
      if (L.spikes > 0.05) {
        ctx.globalCompositeOperation = 'lighter';
        for (let i = 0; i < 4; i++) {
          const ang = t * 1.5 + (i * Math.PI) / 2;
          const len = baseR * (1.4 + 0.3 * Math.sin(t * 3 + i));
          const grad = ctx.createLinearGradient(cx, cy,
            cx + Math.cos(ang) * len, cy + Math.sin(ang) * len);
          grad.addColorStop(0, `rgba(244,175,37,${0.6 * L.spikes})`);
          grad.addColorStop(1, 'rgba(244,175,37,0)');
          ctx.strokeStyle = grad;
          ctx.lineWidth = 1.5 * dpr;
          ctx.beginPath();
          ctx.moveTo(cx, cy);
          ctx.lineTo(cx + Math.cos(ang) * len, cy + Math.sin(ang) * len);
          ctx.stroke();
        }
      }

      ctx.globalCompositeOperation = 'source-over';
      ctx.globalAlpha = 1;

      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [size]);

  return <canvas ref={ref} style={{ display: 'block', borderRadius: '50%' }} />;
}

// ─────────────────────────────────────────────────────────────────────────────
// WebGL tier — fragment shader, FBM-perturbed sphere SDF, SSS-style bleed.
// ─────────────────────────────────────────────────────────────────────────────
const ORB_VS = `
  attribute vec2 a_pos;
  void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
`;

const ORB_FS = `
  precision highp float;
  uniform vec2 u_res;
  uniform float u_time;
  uniform float u_audio;
  uniform float u_bright;
  uniform float u_wobble;
  uniform float u_spikes;
  uniform float u_drift;
  uniform vec3 u_core;
  uniform vec3 u_mid;
  uniform vec3 u_halo;

  // hash + FBM noise — cheap, organic
  float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
  }
  float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    float a = hash(i), b = hash(i + vec2(1,0));
    float c = hash(i + vec2(0,1)), d = hash(i + vec2(1,1));
    vec2 u = f*f*(3.0-2.0*f);
    return mix(mix(a,b,u.x), mix(c,d,u.x), u.y);
  }
  float fbm(vec2 p) {
    float s = 0.0; float a = 0.5;
    for (int i = 0; i < 4; i++) {
      s += a * vnoise(p);
      p *= 2.0; a *= 0.5;
    }
    return s;
  }

  void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_res) / min(u_res.x, u_res.y);
    // dream-drift horizontal sway
    uv.x -= sin(u_time * 0.4) * 0.04 * u_drift;
    uv.y -= cos(u_time * 0.3) * 0.02 * u_drift;

    float r = length(uv);
    float ang = atan(uv.y, uv.x);

    // membrane wobble: FBM along angular coord, time-evolved
    float w = fbm(vec2(ang * 1.7 + u_time * 0.25, u_time * 0.18)) - 0.5;
    float wobble = w * u_wobble * (0.6 + 0.6 * u_audio);

    float radius = 0.34 + wobble * 0.12;

    // SDF distance from membrane (negative inside)
    float d = r - radius;

    // SSS — fake subsurface bleed: thicker fog where r is small
    float thickness = clamp(1.0 - r / radius, 0.0, 1.0);
    float sss = pow(thickness, 1.8);

    // grazing rim — brighten near membrane
    float rim = 1.0 - smoothstep(0.0, 0.06, abs(d));

    // outer halo (atmosphere)
    float halo = exp(-max(d, 0.0) * 7.0);
    float farHalo = exp(-max(d, 0.0) * 2.5) * 0.4;

    // base color blend
    vec3 col = mix(u_mid, u_core, sss);
    col += u_core * rim * 0.6;
    col = mix(col, u_halo, 1.0 - clamp(halo + 0.001, 0.0, 1.0));
    col += u_halo * farHalo;

    // anomaly spikes (sentinel) — radial sin+noise streaks
    if (u_spikes > 0.01) {
      float spk = abs(sin(ang * 6.0 + u_time * 1.4)) *
                  fbm(vec2(ang * 4.0, u_time * 1.2));
      float spikeMask = smoothstep(0.34, 0.65, r) * smoothstep(0.85, 0.5, r);
      col += u_core * spk * spikeMask * u_spikes * 1.4;
    }

    // brightness
    col *= u_bright;

    // alpha — soft sphere body + halo falloff
    float alpha = 1.0;
    if (d > 0.0) {
      alpha = halo * 0.85 + farHalo * 0.5;
    } else {
      alpha = 1.0;
    }
    alpha = clamp(alpha, 0.0, 1.0);

    // gentle vignetting at far edges so we don't paint a hard rect
    alpha *= smoothstep(0.95, 0.5, r);

    gl_FragColor = vec4(col, alpha);
  }
`;

function hexToVec3(hex) {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

function OrbWebGL({ size = 200, look, audio = 0, breathPeriod = 8 }) {
  const ref = React.useRef(null);
  const lookRef = React.useRef(look); lookRef.current = look;
  const audioRef = React.useRef(audio); audioRef.current = audio;
  const breathRef = React.useRef(breathPeriod); breathRef.current = breathPeriod;
  const failedRef = React.useRef(false);
  const [failed, setFailed] = React.useState(false);

  React.useEffect(() => {
    const cnv = ref.current; if (!cnv) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    cnv.width = size * dpr; cnv.height = size * dpr;
    cnv.style.width = size + 'px'; cnv.style.height = size + 'px';

    const gl = cnv.getContext('webgl', { premultipliedAlpha: false, alpha: true, antialias: true });
    if (!gl) { failedRef.current = true; setFailed(true); return; }

    const compile = (src, type) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        console.warn('orb shader err', gl.getShaderInfoLog(s));
        return null;
      }
      return s;
    };
    const vs = compile(ORB_VS, gl.VERTEX_SHADER);
    const fs = compile(ORB_FS, gl.FRAGMENT_SHADER);
    if (!vs || !fs) { failedRef.current = true; setFailed(true); return; }
    const prog = gl.createProgram();
    gl.attachShader(prog, vs); gl.attachShader(prog, fs); gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      failedRef.current = true; setFailed(true); return;
    }
    gl.useProgram(prog);

    // fullscreen quad
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
      -1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1,
    ]), gl.STATIC_DRAW);
    const aPos = gl.getAttribLocation(prog, 'a_pos');
    gl.enableVertexAttribArray(aPos);
    gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);

    const u = (n) => gl.getUniformLocation(prog, n);
    const uRes = u('u_res'), uTime = u('u_time'), uAudio = u('u_audio');
    const uBright = u('u_bright'), uWobble = u('u_wobble');
    const uSpikes = u('u_spikes'), uDrift = u('u_drift');
    const uCore = u('u_core'), uMid = u('u_mid'), uHalo = u('u_halo');

    gl.viewport(0, 0, cnv.width, cnv.height);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

    const start = performance.now();
    let raf;
    const tick = () => {
      const t = (performance.now() - start) / 1000;
      const L = lookRef.current;
      const period = breathRef.current / Math.max(0.2, L.motion);
      const breath = 0.5 + 0.5 * Math.sin((t * 2 * Math.PI) / period);
      const aud = audioRef.current * 0.7 + breath * 0.3;

      gl.uniform2f(uRes, cnv.width, cnv.height);
      gl.uniform1f(uTime, t);
      gl.uniform1f(uAudio, aud);
      gl.uniform1f(uBright, L.bright);
      gl.uniform1f(uWobble, L.wobble);
      gl.uniform1f(uSpikes, L.spikes);
      gl.uniform1f(uDrift, L.drift);
      gl.uniform3fv(uCore, hexToVec3(L.core));
      gl.uniform3fv(uMid, hexToVec3(L.mid));
      gl.uniform3fv(uHalo, hexToVec3(L.halo));

      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.drawArrays(gl.TRIANGLES, 0, 6);

      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [size]);

  if (failed) return <OrbCanvas size={size} look={look} audio={audio} breathPeriod={breathPeriod} />;
  return (
    <canvas ref={ref}
            style={{ display: 'block', opacity: look.opacity, transition: 'opacity 1.2s ease' }} />
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Public Orb component — picks tier + smooths state transitions.
// ─────────────────────────────────────────────────────────────────────────────
function Orb({
  state = 'FOCUS', tier = 'filament', size = 200,
  audio = 0, motionScale = 1, breathPeriod = 8,
  onTap, onLongPress,
}) {
  const look = useSmoothedLook(state, motionScale);

  // press handling
  const pressTimer = React.useRef(null);
  const longPressed = React.useRef(false);
  const handleDown = (e) => {
    longPressed.current = false;
    if (onLongPress) {
      pressTimer.current = setTimeout(() => {
        longPressed.current = true;
        onLongPress(e);
      }, 380);
    }
  };
  const handleUp = (e) => {
    if (pressTimer.current) clearTimeout(pressTimer.current);
    if (!longPressed.current && onTap) onTap(e);
  };
  const handleLeave = () => {
    if (pressTimer.current) clearTimeout(pressTimer.current);
  };

  let inner;
  if (tier === 'compose') inner = <OrbCSS size={size} look={look} audio={audio} breathPeriod={breathPeriod} />;
  else if (tier === 'rendereffect') inner = <OrbCanvas size={size} look={look} audio={audio} breathPeriod={breathPeriod} />;
  else inner = <OrbWebGL size={size} look={look} audio={audio} breathPeriod={breathPeriod} />;

  return (
    <div
      onPointerDown={handleDown}
      onPointerUp={handleUp}
      onPointerLeave={handleLeave}
      style={{
        width: size, height: size, position: 'relative',
        cursor: onTap || onLongPress ? 'pointer' : 'default',
        touchAction: 'none',
      }}>
      {inner}
    </div>
  );
}

Object.assign(window, { Orb, ORB_STATE_LOOK });
