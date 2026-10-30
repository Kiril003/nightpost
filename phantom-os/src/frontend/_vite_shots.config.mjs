import base from './vite.config.ts';
export default async (env) => {
  const c = typeof base === 'function' ? await base(env) : base;
  return { ...c, server: { ...(c.server || {}), fs: { strict: false } } };
};
