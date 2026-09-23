/* SSE = server-sent events（服务器推送事件）。fetch 允许认证头，凭据不进入 URL。 */
(() => {
  "use strict";
  function parser(onEvent) {
    let buffer = "";
    return chunk => {
      buffer += chunk;
      let boundary;
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index);
        if (frame.length > 131072) throw new Error("STREAM_EVENT_TOO_LARGE");
        buffer = buffer.slice(boundary.index + boundary[0].length);
        let name = "message", id = "", data = [];
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith("event:")) name = line.slice(6).trim();
          else if (line.startsWith("id:")) id = line.slice(3).trim();
          else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
        }
        if (data.length) onEvent({ name, id, data: JSON.parse(data.join("\n")) });
      }
      if (buffer.length > 131072) throw new Error("STREAM_EVENT_TOO_LARGE");
    };
  }
  async function connect({ url, auth, signal, onEvent }) {
    const response = await fetch(url, { headers: { Authorization: auth, "X-Requested-With": "XMLHttpRequest", Accept: "text/event-stream" },
      credentials: "same-origin", cache: "no-store", signal });
    if (!response.ok || !response.body || !response.headers.get("content-type")?.includes("text/event-stream"))
      throw new Error("STREAM_UNAVAILABLE");
    const reader = response.body.getReader(), decoder = new TextDecoder(), push = parser(onEvent);
    try {
      while (true) {
        const part = await reader.read();
        if (part.done) { push(decoder.decode()); break; }
        push(decoder.decode(part.value, { stream: true }));
      }
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  }
  globalThis.EcomStream = { parser, connect };
})();
