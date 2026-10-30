import type { LottiePlayer } from 'lottie-web';

let player: Promise<LottiePlayer> | null = null;

/** Легкий програвач — окремим шматком, лише коли на екрані перший стікер. */
export function loadLottie(): Promise<LottiePlayer> {
  if (!player) {
    player = import('lottie-web/build/player/lottie_light').then((m) => (m.default ?? m) as LottiePlayer);
    player.catch(() => {
      player = null;
    });
  }
  return player;
}
