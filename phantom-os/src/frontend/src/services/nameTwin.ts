import { normName } from './money/lookalike';

interface TwinRow {
  peer_node_id: string;
  display_name: string;
  verified: boolean;
  key_changed?: boolean;
}

/** Звірена людина з тим самим іменем на ІНШОМУ ключі; для вже звіреного — нічого. */
export function verifiedTwin(peerNodeId: string, name: string, rows: readonly TwinRow[]): string | null {
  const own = rows.find((r) => r.peer_node_id === peerNodeId);
  if (own?.verified && !own.key_changed) return null;
  const want = normName(name);
  if (!want) return null;
  const twin = rows.find((r) => r.peer_node_id !== peerNodeId && r.verified && !r.key_changed && normName(r.display_name) === want);
  return twin ? twin.display_name : null;
}
