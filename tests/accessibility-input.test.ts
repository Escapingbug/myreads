import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ExplorationController } from "../src/services/accessibility-input";

describe("无视觉触摸确认", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());
  it("探索不会执行，第二次点按别处仍确认之前选中的功能", () => {
    const speak = vi.fn(), activate = vi.fn();
    const input = new ExplorationController(speak, activate);
    input.down("shelf", 20, 20, 1000); input.up(1050);
    expect(speak).toHaveBeenCalledWith("shelf"); expect(activate).not.toHaveBeenCalled();
    input.down("exit", 200, 300, 1200); input.up(1250);
    expect(activate).toHaveBeenCalledExactlyOnceWith("shelf");
    expect(speak).not.toHaveBeenCalledWith("exit");
  });
  it("拖动探索与超时的新点按不构成确认", () => {
    const activate = vi.fn(); const input = new ExplorationController(vi.fn(), activate);
    input.down("shelf", 0, 0, 1000); input.move("history", 50, 50); input.up(1050);
    input.down("history", 50, 50, 1100); input.up(1150);
    input.down("find", 50, 50, 2000); input.up(2050);
    vi.advanceTimersByTime(401);
    expect(activate).not.toHaveBeenCalled(); expect(input.selected).toBe("find");
  });
  it("拖动选中后，两个确认点都落在别处仍执行原来的选择", () => {
    const speak = vi.fn(), activate = vi.fn(); const input = new ExplorationController(speak, activate);
    input.down("shelf", 0, 0, 1000); input.move("history", 50, 50); input.up(1100);
    input.down("exit", 200, 200, 1500); input.up(1550);
    input.down("find", 300, 300, 1700); input.up(1750);
    vi.advanceTimersByTime(500);
    expect(activate).toHaveBeenCalledExactlyOnceWith("history");
    expect(speak).not.toHaveBeenCalledWith("exit"); expect(speak).not.toHaveBeenCalledWith("find");
  });
  it("切换页面后无法双击执行上一页的选择", () => {
    const activate = vi.fn(); const input = new ExplorationController(vi.fn(), activate);
    input.down("next", 0, 0, 1000); input.up(1050); input.reset();
    input.down("back", 0, 0, 1100); input.up(1150);
    expect(activate).not.toHaveBeenCalled();
  });
});
