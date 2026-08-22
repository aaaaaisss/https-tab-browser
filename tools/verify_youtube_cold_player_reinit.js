const fs = require('fs');

const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const marker = 'val YOUTUBE_COLD_PLAYER_REINIT_SCRIPT = """';
const start = source.indexOf(marker);
if (start < 0) throw new Error('Cold player reinit script marker not found');
const end = source.indexOf('""".trimIndent()', start);
if (end < 0) throw new Error('Cold player reinit script terminator not found');
const script = source.slice(start + marker.length, end);
new Function(script);
for (const forbidden of ['location.reload', 'ytInitialPlayerResponse=', 'delete window.ytInitialPlayerResponse']) {
  if (script.includes(forbidden)) throw new Error(`Cold player script must not use ${forbidden}`);
}

function runScenario({ path = '/watch', hadInitialAds = true, isLive = false, currentTime = 0 }) {
  const queue = [];
  const timers = (callback, delay) => queue.push({ callback, delay });
  const calls = { cancel: 0, load: [] };
  const player = {
    cancelPlayback() { calls.cancel += 1; },
    loadVideoById(id) { calls.load.push(id); },
    getVideoData() { return { isLive }; },
  };
  const video = { currentTime };
  const window = { __nekoBrowserInitialPlayerHadAds: hadInitialAds };
  const document = {
    querySelector(selector) {
      if (selector === '#movie_player') return player;
      if (selector === 'video') return video;
      return null;
    },
  };
  const location = { pathname: path, href: `https://www.youtube.com${path}?v=cold-test` };
  new Function('window', 'location', 'URL', 'document', 'setTimeout', script)(
    window,
    location,
    URL,
    document,
    timers,
  );
  while (queue.length) queue.shift().callback();
  return calls;
}

const vod = runScenario({});
if (vod.cancel !== 1 || vod.load.join(',') !== 'cold-test') {
  throw new Error(`Eligible cold VOD must reinit exactly once, got ${JSON.stringify(vod)}`);
}
for (const scenario of [
  { path: '/shorts', hadInitialAds: true },
  { path: '/watch', hadInitialAds: false },
  { path: '/watch', hadInitialAds: true, isLive: true },
  { path: '/watch', hadInitialAds: true, currentTime: 1 },
]) {
  const calls = runScenario(scenario);
  if (calls.cancel !== 0 || calls.load.length !== 0) {
    throw new Error(`Ineligible navigation must remain untouched: ${JSON.stringify({ scenario, calls })}`);
  }
}

console.log('YouTube conditional cold player reinit safety behavior: OK');
