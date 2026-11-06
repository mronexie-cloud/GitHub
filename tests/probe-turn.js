// Which public STUN/TURN servers hand out candidates (srflx = STUN works, relay = TURN works)?
const { chromium } = require('playwright');
const CFG = {
  google_stun: { iceServers: [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }] },
  cloudflare_stun: { iceServers: [{ urls: 'stun:stun.cloudflare.com:3478' }] },
  openrelay_turn: { iceServers: [{ urls: ['turn:openrelay.metered.ca:80', 'turn:openrelay.metered.ca:443', 'turn:openrelay.metered.ca:443?transport=tcp'], username: 'openrelayproject', credential: 'openrelayproject' }], iceTransportPolicy: 'relay' },
  freestun_turn: { iceServers: [{ urls: ['turn:freestun.net:3478', 'turns:freestun.net:5350'], username: 'free', credential: 'free' }], iceTransportPolicy: 'relay' },
  freeturn_tcp: { iceServers: [{ urls: ['turn:freestun.net:3478?transport=tcp'], username: 'free', credential: 'free' }], iceTransportPolicy: 'relay' },
};
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  await p.goto('https://mronexie-cloud.github.io/GitHub/');
  const out = await p.evaluate(async (CFG) => {
    const res = {};
    for (const [name, cfg] of Object.entries(CFG)) {
      const pc = new RTCPeerConnection(cfg); pc.createDataChannel('x');
      const types = {};
      pc.onicecandidate = e => { if (e.candidate && e.candidate.candidate) { const t = (e.candidate.candidate.match(/ typ (\w+)/) || [])[1]; types[t] = (types[t] || 0) + 1; } };
      await pc.setLocalDescription(await pc.createOffer());
      await new Promise(r => setTimeout(r, 7000));
      res[name] = types; pc.close();
    }
    return res;
  }, CFG);
  for (const [k, v] of Object.entries(out)) console.log(k.padEnd(16), JSON.stringify(v));
  await b.close();
})();
