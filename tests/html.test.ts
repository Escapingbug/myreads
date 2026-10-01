import { expect, it } from "vitest";
import { parseHTML } from "../src/services/html";

it("目录展开返回的 HTML 片段会保留在 body 中", () => {
  const page = parseHTML(
    '<html><body><ul class="list3"><li><a href="1.html">第一章</a></li><div id="detail"></div><li><a href="4.html">第四章</a></li></ul></body></html>',
  );
  const fragment = parseHTML(
    '<li><a href="2.html">第二章</a></li><li><a href="3.html">第三章</a></li>',
  );
  page.querySelector("#detail")!.replaceChildren(...fragment.body.childNodes);
  expect(
    [...page.querySelectorAll(".list3 a")].map((link) =>
      link.getAttribute("href"),
    ),
  ).toEqual(["1.html", "2.html", "3.html", "4.html"]);
});
