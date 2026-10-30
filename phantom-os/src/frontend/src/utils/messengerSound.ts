/**
 * PHANTOM OS — Web Audio API Sound Synthesizer for Tactile Haptic Feedback
 * Generates purely synthesized, zero-latency organic UI earcons & sounds
 * without needing external mp3/wav files.
 */

import { haptic } from './haptic';

class SoundEffectsManager {
  private ctx: AudioContext | null = null;
  public enabled: boolean = true;

  private initContext() {
    if (!this.ctx && typeof window !== 'undefined') {
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      if (AudioCtx) {
        this.ctx = new AudioCtx();
      }
    }
  }

  private triggerHaptic(type: 'tap' | 'send' | 'receive' | 'chime' | 'error') {
    if (typeof window !== 'undefined') {
      try {
        const bridge = (window as unknown as { PhantomBridge?: { haptic?: (t: string) => void } }).PhantomBridge;
        if (bridge && typeof bridge.haptic === 'function') {
          haptic(type);
        } else if (navigator.vibrate) {
          const duration = type === 'send' ? 25 : type === 'chime' ? 40 : 15;
          navigator.vibrate(duration);
        }
      } catch {
        // ignore
      }
    }
  }

  // Soft organic bubble pop for sent message
  playSend() {
    this.triggerHaptic('send');
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;
      if (this.ctx.state === 'suspended') this.ctx.resume();

      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();

      osc.type = 'sine';
      const now = this.ctx.currentTime;
      osc.frequency.setValueAtTime(420, now);
      osc.frequency.exponentialRampToValueAtTime(780, now + 0.08);

      gain.gain.setValueAtTime(0.12, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.12);

      osc.connect(gain);
      gain.connect(this.ctx.destination);

      osc.start(now);
      osc.stop(now + 0.12);
    } catch {
      // ignore
    }
  }

  // Soft wooden tap / reception sound
  playReceive(opts?: { haptic?: boolean }) {
    if (opts?.haptic !== false) this.triggerHaptic('receive');
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;
      if (this.ctx.state === 'suspended') this.ctx.resume();

      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();

      osc.type = 'triangle';
      const now = this.ctx.currentTime;
      osc.frequency.setValueAtTime(600, now);
      osc.frequency.exponentialRampToValueAtTime(320, now + 0.09);

      gain.gain.setValueAtTime(0.1, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.1);

      osc.connect(gain);
      gain.connect(this.ctx.destination);

      osc.start(now);
      osc.stop(now + 0.1);
    } catch {
      // ignore
    }
  }

  // Gentle chime for AI dossier generated or action triggered
  playChime(opts?: { haptic?: boolean }) {
    if (opts?.haptic !== false) this.triggerHaptic('chime');
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;
      if (this.ctx.state === 'suspended') this.ctx.resume();

      const notes = [523.25, 659.25, 783.99]; // C5, E5, G5
      const now = this.ctx.currentTime;

      notes.forEach((freq, idx) => {
        const osc = this.ctx!.createOscillator();
        const gain = this.ctx!.createGain();

        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, now + idx * 0.04);

        gain.gain.setValueAtTime(0.08, now + idx * 0.04);
        gain.gain.exponentialRampToValueAtTime(0.001, now + idx * 0.04 + 0.25);

        osc.connect(gain);
        gain.connect(this.ctx!.destination);

        osc.start(now + idx * 0.04);
        osc.stop(now + idx * 0.04 + 0.25);
      });
    } catch {
      // ignore
    }
  }

  // Subtle tactile tap
  playTap(opts?: { haptic?: boolean }) {
    if (opts?.haptic !== false) this.triggerHaptic('tap');
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;
      if (this.ctx.state === 'suspended') this.ctx.resume();

      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();

      osc.type = 'sine';
      const now = this.ctx.currentTime;
      osc.frequency.setValueAtTime(240, now);
      osc.frequency.exponentialRampToValueAtTime(120, now + 0.03);

      gain.gain.setValueAtTime(0.06, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.03);

      osc.connect(gain);
      gain.connect(this.ctx.destination);

      osc.start(now);
      osc.stop(now + 0.03);
    } catch {
      // ignore
    }
  }

  // Celebratory sound for confetti & reactions
  playConfetti() {
    this.playChime();
  }

  // Warning / error tactile buzz
  playError() {
    this.triggerHaptic('error');
  }
}

export const soundFx = new SoundEffectsManager();
