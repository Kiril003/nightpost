import { describe, expect, it } from 'vitest';
import { symbioteApi } from '../services/api';

describe('мертві дороги помічника прибрано', () => {
  it('symbioteApi не має команди, яку ніхто не кличе', () => {
    expect(Object.keys(symbioteApi)).toEqual(['state']);
  });
});
