const HOLD_MS = 600;
const MOVE_SLOP = 16;
const SWIPE_DISTANCE = 64;

/** Short taps execute on release; holding explains without executing. Movement
 * cancels the tap and may become one horizontal previous/next gesture. */
export class DirectGestureController {
  private active = false;
  private moved = false;
  private held = false;
  private candidate = "";
  private started = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private origin = { x: 0, y: 0 };
  constructor(
    private describe: (id: string) => void,
    private activate: (id: string) => void,
    private swipe: (delta: -1 | 1) => void,
  ) {}
  reset() {
    this.clearTimer(); this.active = false; this.candidate = "";
    this.held = false; this.moved = false;
  }
  down(id: string, x: number, y: number, now: number) {
    this.reset(); this.active = true; this.candidate = id;
    this.origin = { x, y }; this.started = now;
    this.timer = setTimeout(() => {
      if (this.active && !this.moved) {
        this.held = true;
        if (this.candidate) this.describe(this.candidate);
      }
    }, HOLD_MS);
  }
  move(x: number, y: number) {
    if (this.active && Math.hypot(x - this.origin.x, y - this.origin.y) > MOVE_SLOP) {
      this.moved = true; this.clearTimer();
    }
  }
  up(id: string, x: number, y: number, now: number) {
    if (!this.active) return;
    this.move(x, y);
    const candidate = this.candidate, held = this.held, moved = this.moved;
    const elapsed = now - this.started, dx = x - this.origin.x, dy = y - this.origin.y;
    this.reset();
    if (held) return;
    if (moved) {
      if (Math.abs(dx) >= SWIPE_DISTANCE && Math.abs(dx) >= Math.abs(dy) * 1.5) this.swipe(dx < 0 ? 1 : -1);
      return;
    }
    if (!candidate || id !== candidate) return;
    // A busy UI thread may deliver pointerup before the long-press timer runs.
    if (elapsed >= HOLD_MS) this.describe(candidate);
    else this.activate(candidate);
  }
  private clearTimer() { if (this.timer !== undefined) clearTimeout(this.timer); this.timer = undefined; }
}
