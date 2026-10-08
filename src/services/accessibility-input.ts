/** Touch exploration is separate from activation. A second tap anywhere confirms
 * the previous selection, rather than changing it under the second finger. */
export class ExplorationController {
  selected = "";
  private lastTap = 0;
  private confirming = false;
  private moved = false;
  private held = false;
  private candidate = "";
  private deferred = false;
  private timer?: ReturnType<typeof setTimeout>;
  private origin = { x: 0, y: 0 };
  constructor(private describe: (id: string) => void, private activate: (id: string) => void) {}
  reset() { this.clearTimer(); this.selected = ""; this.lastTap = 0; this.confirming = false; }
  down(id: string, x: number, y: number, now: number) {
    this.clearTimer(); this.origin = { x, y }; this.moved = false; this.held = false; this.candidate = id;
    this.confirming = this.lastTap > 0 && now - this.lastTap <= 400 && !!this.selected;
    this.deferred = !!this.selected;
    if (this.confirming) return;
    if (!this.selected) this.select(id);
    else this.timer = setTimeout(() => { this.held = true; this.select(id, true); }, 400);
  }
  move(id: string, x: number, y: number) {
    if (Math.hypot(x - this.origin.x, y - this.origin.y) > 12) this.moved = true;
    if (!this.confirming && this.moved) { this.clearTimer(); this.select(id); }
  }
  up(now: number) {
    this.clearTimer();
    if (this.moved || this.held) { this.lastTap = 0; this.confirming = false; return; }
    if (this.confirming && this.selected) {
      const id = this.selected; this.lastTap = 0; this.confirming = false; this.activate(id);
    } else {
      this.lastTap = now;
      // Wait for the second tap before changing a pre-existing selection. This
      // also preserves the explored target when both confirmation taps land elsewhere.
      if (this.deferred) this.timer = setTimeout(() => this.select(this.candidate, true), 400);
    }
  }
  private clearTimer() { if (this.timer) clearTimeout(this.timer); this.timer = undefined; }
  private select(id: string, repeat = false) {
    if (!id || id === this.selected && !repeat) return;
    this.selected = id; this.describe(id);
  }
}
