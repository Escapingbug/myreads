/** Keep enlarged labels inside their touch targets. The targets retain their
 * viewport allocation; only labels that would overflow are reduced. */
export function fitAccessibleLabels(root: HTMLElement) {
  for (const element of root.querySelectorAll<HTMLElement>("button:not(.a11y-summary), h1, .a11y-feedback")) {
    element.style.removeProperty("--a11y-label-scale");
    const base = parseFloat(getComputedStyle(element).fontSize);
    const minimum = element.classList.contains("a11y-exit-shortcut") ? 12 : 16;
    let scale = 1;
    while ((element.scrollHeight > element.clientHeight + 1 || element.scrollWidth > element.clientWidth + 1)
      && base * scale > minimum) {
      scale = Math.max(minimum / base, scale * .9);
      element.style.setProperty("--a11y-label-scale", String(scale));
    }
  }
}
