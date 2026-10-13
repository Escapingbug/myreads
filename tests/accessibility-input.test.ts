import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DirectGestureController } from "../src/services/accessibility-input";

describe("无障碍单击、长按与滑动", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());
  it("单击松手即执行，不先播报，也不复用前一个按钮", () => {
    const speak = vi.fn(), activate = vi.fn();
    const input = new DirectGestureController(speak, activate, vi.fn());
    input.down("shelf", 20, 20, 1000);
    expect(activate).not.toHaveBeenCalled();
    input.up("shelf", 20, 20, 1050);
    input.down("exit", 200, 300, 1200); input.up("exit", 200, 300, 1250);
    expect(activate.mock.calls).toEqual([["shelf"], ["exit"]]);
    expect(speak).not.toHaveBeenCalled();
  });
  it("长按只说明一次，松手或随后移动都不执行", () => {
    const speak = vi.fn(), activate = vi.fn(), swipe = vi.fn();
    const input = new DirectGestureController(speak, activate, swipe);
    input.down("shelf", 200, 100, 1000);
    vi.advanceTimersByTime(1600);
    expect(speak).toHaveBeenCalledExactlyOnceWith("shelf");
    input.move(50, 100); input.up("find", 50, 100, 2700);
    expect(activate).not.toHaveBeenCalled(); expect(swipe).not.toHaveBeenCalled();
  });
  it("计时器被阻塞时，长按松手也不会变成单击", () => {
    const speak = vi.fn(), activate = vi.fn(); const input = new DirectGestureController(speak, activate, vi.fn());
    input.down("exit", 0, 0, 1000); input.up("exit", 0, 0, 1800);
    vi.advanceTimersByTime(1000);
    expect(speak).toHaveBeenCalledExactlyOnceWith("exit"); expect(activate).not.toHaveBeenCalled();
  });
  it("左滑下一项、右滑上一项，每次松手仅切换一次且不会点击途经按钮", () => {
    const speak = vi.fn(), activate = vi.fn(), swipe = vi.fn(); const input = new DirectGestureController(speak, activate, swipe);
    input.down("choose", 240, 100, 1000); input.move(150, 110); input.move(80, 115);
    expect(swipe).not.toHaveBeenCalled();
    input.up("exit", 60, 115, 1300); input.up("exit", 60, 115, 1350);
    input.down("", 60, 100, 2000); input.up("choose", 200, 105, 2300);
    expect(swipe.mock.calls).toEqual([[1], [-1]]);
    vi.advanceTimersByTime(1000);
    expect(activate).not.toHaveBeenCalled(); expect(speak).not.toHaveBeenCalled();
  });
  it("竖向滚动、短拖动、移出按钮均不会执行", () => {
    const activate = vi.fn(), swipe = vi.fn(); const input = new DirectGestureController(vi.fn(), activate, swipe);
    input.down("choose", 0, 0, 1000); input.up("choose", 30, 140, 1300);
    input.down("choose", 0, 0, 2000); input.up("choose", 30, 0, 2300);
    input.down("choose", 0, 0, 3000); input.up("", 4, 0, 3300);
    input.down("choose", 0, 0, 4000); input.move(30, 0); input.up("choose", 0, 0, 4300);
    expect(activate).not.toHaveBeenCalled(); expect(swipe).not.toHaveBeenCalled();
  });
  it("切页或系统取消触摸后不执行也不继续播报", () => {
    const speak = vi.fn(), activate = vi.fn(), swipe = vi.fn(); const input = new DirectGestureController(speak, activate, swipe);
    input.down("exit", 0, 0, 1000); input.reset(); input.up("exit", 0, 0, 1200);
    vi.advanceTimersByTime(1000);
    expect(activate).not.toHaveBeenCalled(); expect(speak).not.toHaveBeenCalled(); expect(swipe).not.toHaveBeenCalled();
  });
});
