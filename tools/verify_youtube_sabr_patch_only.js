const fs = require('fs');
const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const marker = 'val YOUTUBE_SABR_PATCH_ONLY_SCRIPT = """';
const start = source.indexOf(marker);
if (start < 0) throw new Error('Patch-only SABR script marker not found');
const end = source.indexOf('""".trimIndent()', start);
if (end < 0) throw new Error('Patch-only SABR script terminator not found');
const script = source.slice(start + marker.length, end);
new Function(script);
for (const forbidden of ['cancelPlayback', 'loadVideoById', 'isInlinePlaybackNoAd', 'Object.assign']) {
  if (script.includes(forbidden)) throw new Error(`Patch-only script must not use ${forbidden}`);
}

(async () => {
  const original = new Uint8Array([0x20, 0xd8, 0x09]); // field 4: 1240 ms
  class FakeXmlHttpRequest {
    constructor() {
      this.listeners = {};
      this.response = original.buffer.slice(0);
    }
    open() {}
    send() {
      const listener = this.listeners.loadend;
      if (listener) listener.call(this);
    }
    addEventListener(name, listener) { this.listeners[name] = listener; }
  }
  const window = {
    fetch: () => Promise.resolve(new Response(original)),
  };
  const document = { querySelector: () => null };
  new Function('window', 'document', 'Response', 'Uint8Array', 'XMLHttpRequest', script)(
    window, document, Response, Uint8Array, FakeXmlHttpRequest,
  );
  const response = await window.fetch('https://rr1---sn.googlevideo.com/videoplayback?sabr=1');
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.length !== original.length || bytes[0] !== 0x20 || bytes[1] === original[1]) {
    throw new Error('SABR fetch backoff response was not patched in place');
  }

  const xhr = new FakeXmlHttpRequest();
  xhr.open('GET', 'https://rr1---sn.googlevideo.com/videoplayback?sabr=1');
  xhr.send();
  const xhrBytes = new Uint8Array(xhr.response);
  if (xhrBytes.length !== original.length || xhrBytes[0] !== 0x20 || xhrBytes[1] === original[1]) {
    throw new Error('SABR XHR backoff response was not patched in place');
  }

  const failingWindow = {
    fetch: () => Promise.resolve(new Response(new ReadableStream({
      start(controller) { controller.error(new Error('simulated SABR read failure')); },
    }))),
  };
  new Function('window', 'document', 'Response', 'Uint8Array', script)(
    failingWindow,
    document,
    Response,
    Uint8Array,
  );
  let rejected = false;
  try {
    await failingWindow.fetch('https://rr1---sn.googlevideo.com/videoplayback?sabr=1');
  } catch (_error) {
    rejected = true;
  }
  if (!rejected) throw new Error('SABR read failure must propagate instead of returning a synthetic success response');
  console.log('YouTube SABR patch-only session-preserving behavior: OK');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
