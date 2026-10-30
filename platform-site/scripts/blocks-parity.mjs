import { readFileSync } from 'node:fs';
import { REGISTRY } from '../src/lib/blocks/registry.ts';
import { validatePageV2 } from '../src/lib/blocks/validate.ts';
import { up, down } from '../src/lib/blocks/migrate.ts';

const input = JSON.parse(readFileSync(0, 'utf8'));
const out = {
  verdicts: input.verdicts.map((c) => validatePageV2(c.page, c.registry ?? REGISTRY, { publish: Boolean(c.publish) })),
  up: input.up.map((p) => up(p)),
  down: input.down.map((p) => down(p)),
};
process.stdout.write(JSON.stringify(out));
