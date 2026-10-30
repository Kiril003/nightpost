/**
 * «Твоє місце» — рельєф під розмовою (ідентичність 02.10).
 *
 * Ізолінії поля висот, як на топографічній мапі: тонка лінія кожні N метрів
 * і трохи густіша «потовщена» кожна п'ята. Поле — генеративне, але ЗАСІЯНЕ
 * місцем людини (грубо, ~10 км: `currentSunPlace`, яке вже знає тема), тож
 * у Києві і у Львові розмови лежать на різному рельєфі, а в одного телефона
 * він той самий щодня. Справжні висоти з офлайн-мапи — наступний крок; тут
 * чесна генеративна заміна, і вона ніде не видає себе за мапу.
 *
 * Чисто: без DOM, без випадковості поза зерном. Сторож — terrain.test.ts.
 */

/** mulberry32 — малий сталий ГПВЧ із зерна. */
const rng = (seed: number) => {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
};

/** Зерно з місця: ~0.1° сітка (≈ 10 км) — район, не адреса. */
export const terrainSeed = (lat: number, lon: number): number => {
  const a = Math.round(lat * 10);
  const b = Math.round(lon * 10);
  let h = 0x811c9dc5;
  for (const c of `${a}:${b}`) {
    h ^= c.charCodeAt(0);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
};

/** Гладкий шум значень на решітці, кілька октав. */
const makeField = (seed: number, aspect: number) => {
  const r = rng(seed);
  const G = 64;
  const grid = Array.from({ length: G * G }, () => r());
  const at = (x: number, y: number) => grid[((y % G) + G) % G * G + (((x % G) + G) % G)];
  const smooth = (t: number) => t * t * (3 - 2 * t);
  const noise = (x: number, y: number) => {
    const x0 = Math.floor(x);
    const y0 = Math.floor(y);
    const fx = smooth(x - x0);
    const fy = smooth(y - y0);
    const a = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx;
    const b = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx;
    return a + (b - a) * fy;
  };
  // Два-три «пагорби» дають рельєфу характер, якого не має чистий шум.
  const hills = Array.from({ length: 4 }, () => ({ x: r(), y: r() * aspect, s: 0.12 + r() * 0.2, h: 0.5 + r() * 0.6 }));
  return (u: number, v: number) => {
    let z = 0;
    let amp = 1;
    let f = 2.2;
    for (let o = 0; o < 4; o += 1) {
      z += noise(u * f + o * 17.3, v * f + o * 9.1) * amp;
      amp *= 0.5;
      f *= 2.03;
    }
    for (const hl of hills) {
      const d2 = ((u - hl.x) ** 2 + ((v - hl.y) * 0.6) ** 2) / (hl.s * hl.s);
      z += hl.h * Math.exp(-d2);
    }
    return z;
  };
};

export interface Terrain {
  /** Тонкі ізолінії. */
  lines: string;
  /** Кожна п'ята — «потовщена». */
  index: string;
  width: number;
  height: number;
}

/**
 * Ізолінії «марширувальними квадратами»: точки на ребрах рахуються з ключа
 * ребра, тож сусідні клітинки сходяться в ту саму точку і відрізки
 * склеюються в полілінії (менший шлях, рівніший штрих).
 */
export function terrain(seed: number, width = 400, height = 900, cell = 6, levels = 22): Terrain {
  const field = makeField(seed, height / width);
  const nx = Math.ceil(width / cell) + 1;
  const ny = Math.ceil(height / cell) + 1;
  const z = new Float32Array(nx * ny);
  let lo = Infinity;
  let hi = -Infinity;
  for (let j = 0; j < ny; j += 1) {
    for (let i = 0; i < nx; i += 1) {
      const v = field((i * cell) / width, (j * cell) / width);
      z[j * nx + i] = v;
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    }
  }
  const step = (hi - lo) / (levels + 1);
  const fine: string[] = [];
  const bold: string[] = [];
  for (let k = 1; k <= levels; k += 1) {
    const iso = lo + k * step;
    const pt = new Map<string, [number, number]>();
    const edge = (i0: number, j0: number, i1: number, j1: number): string => {
      const key = i0 < i1 || j0 < j1 ? `${i0},${j0},${i1},${j1}` : `${i1},${j1},${i0},${j0}`;
      if (!pt.has(key)) {
        const a = z[j0 * nx + i0];
        const b = z[j1 * nx + i1];
        const t = (iso - a) / (b - a);
        pt.set(key, [(i0 + (i1 - i0) * t) * cell, (j0 + (j1 - j0) * t) * cell]);
      }
      return key;
    };
    const segs: Array<[string, string]> = [];
    for (let j = 0; j < ny - 1; j += 1) {
      for (let i = 0; i < nx - 1; i += 1) {
        const c =
          (z[j * nx + i] > iso ? 8 : 0) |
          (z[j * nx + i + 1] > iso ? 4 : 0) |
          (z[(j + 1) * nx + i + 1] > iso ? 2 : 0) |
          (z[(j + 1) * nx + i] > iso ? 1 : 0);
        if (c === 0 || c === 15) continue;
        const T = () => edge(i, j, i + 1, j);
        const R = () => edge(i + 1, j, i + 1, j + 1);
        const B = () => edge(i, j + 1, i + 1, j + 1);
        const L = () => edge(i, j, i, j + 1);
        switch (c) {
          case 1: case 14: segs.push([L(), B()]); break;
          case 2: case 13: segs.push([B(), R()]); break;
          case 3: case 12: segs.push([L(), R()]); break;
          case 4: case 11: segs.push([T(), R()]); break;
          case 6: case 9: segs.push([T(), B()]); break;
          case 7: case 8: segs.push([L(), T()]); break;
          case 5: segs.push([L(), T()], [B(), R()]); break;
          case 10: segs.push([T(), R()], [L(), B()]); break;
        }
      }
    }
    // Склеїти відрізки в полілінії.
    const ends = new Map<string, number[]>();
    segs.forEach(([a, b], n) => {
      (ends.get(a) ?? ends.set(a, []).get(a)!).push(n);
      (ends.get(b) ?? ends.set(b, []).get(b)!).push(n);
    });
    const used = new Uint8Array(segs.length);
    const out = k % 5 === 0 ? bold : fine;
    const f = (p: [number, number]) => `${p[0].toFixed(1)} ${p[1].toFixed(1)}`;
    for (let n = 0; n < segs.length; n += 1) {
      if (used[n]) continue;
      used[n] = 1;
      const chain = [segs[n][0], segs[n][1]];
      for (const dir of [1, 0]) {
        for (;;) {
          const tip = dir ? chain[chain.length - 1] : chain[0];
          const next = (ends.get(tip) ?? []).find((m) => !used[m]);
          if (next === undefined) break;
          used[next] = 1;
          const [a, b] = segs[next];
          const other = a === tip ? b : a;
          if (dir) chain.push(other);
          else chain.unshift(other);
        }
      }
      out.push(`M${chain.map((key) => f(pt.get(key)!)).join('L')}`);
    }
  }
  return { lines: fine.join(''), index: bold.join(''), width, height };
}
