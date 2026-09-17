# Lofi server

Turns a Lofi Girl YouTube live radio into WebM/Opus that a plain `<audio>`
element can play. Split out of [lofi-wallpaper](../lofi-wallpaper), which is the
client it was written for; [lofi-bot](../lofi-bot) is the other one.

```
src/               the Spring Boot server
Dockerfile         the server plus the ffmpeg it shells out to
docker-compose.yml how it runs on the VM
Caddyfile          only for the optional tls profile
```

## Why this exists

The wallpaper is a `file://` page and cannot play a YouTube live stream on its
own. Four things had to be true; two turned out not to be obstacles, two are.

| | Measured | Blocking? |
|---|---|---|
| CORS on YouTube's API | `Content-Type: text/plain` skips the preflight, and the response carries `Access-Control-Allow-Origin: null` | no |
| A PO token is required | `PO Token Providers: none` in yt-dlp's own log. The earlier `FAILED_PRECONDITION` was a stale `clientVersion`; `21.02.35` answers `OK` for every station | no |
| CORS on googlevideo | Segments answer `206` with **no** `Access-Control-Allow-Origin`, so `fetch()` cannot read them - which also rules out ffmpeg.wasm, since it only works on bytes JavaScript already holds | **yes** |
| Codecs in Wallpaper Engine's CEF | `canPlayType` answers `""` for AAC and H.264, and a live radio serves only `avc1 + mp4a`. Opus in WebM answers `"probably"` | **yes** |

So the server resolves the stream, downloads it, throws the video away and
transcodes the audio to Opus. The client plays the result.

## Running it

```bash
mvn package -DskipTests
java -jar target/lofi-server.jar
```

Or in Docker, which brings its own ffmpeg:

```bash
docker compose up -d --build
```

| Endpoint | |
|---|---|
| `GET /stream?id=<videoId>&q=<kbps>` | the audio, as WebM/Opus |
| `GET /api/health` | ffmpeg version and the live stations |
| `GET /api/resolve?id=<videoId>` | resolving on its own, for diagnosis |

`/api/resolve` is the first thing to run against a new host: it is the call that
fails when an IP sits in a range YouTube treats as a datacenter, and it costs no
bandwidth worth counting.

## How it works

One ffmpeg per station, not per listener. On a small host the CPU limit binds
long before bandwidth does, and sharing doubles as the latency fix: joining a
station that is already running costs about one cluster (~1 s) against the ~3.8 s
a cold start takes - 0.65 s to resolve, then ~3.1 s before ffmpeg emits anything.

Sharing a live WebM stream means new listeners cannot simply be handed the
current bytes: they need the EBML header and Tracks first. `StationStream` keeps
that init segment and splices each new listener in at the next cluster boundary.

A station is stopped 15 seconds after its last listener leaves. The delay is not
politeness - starting one makes ffmpeg pull the whole HLS window at once, costing
roughly twice the steady rate for the first twenty seconds, so riding out a brief
reconnect is cheaper than paying that again.

## Deploying to Oracle Cloud

`.github/workflows/deploy.yml` rsyncs this repo to a VM over SSH and rebuilds the
container there. The build also runs in CI, but only as a check - the VM rebuilds
its own image, because shipping a 20 MB jar on every push is slower than letting
Docker reuse its layer cache on the far end.

Set as repository secrets: `SSH_HOST` (the VM's public IP), `SSH_USER` (`ubuntu`
on an Ubuntu image, `opc` on Oracle Linux) and `SSH_KEY` (the private key whose
public half is in the VM's `authorized_keys`).

On the VM itself:

1. **Pick Ampere A1, not the AMD micro.** Always Free gives an A1 flex 2 OCPU /
   12 GB against the micro's 1/8 OCPU / 1 GB, and `mem_limit` here is 768m. Both
   base images have `arm64`, so nothing needs changing for Arm. Always Free
   instances must be created in the tenancy's home region.
2. **Open 8477 in two places.** Oracle's Ubuntu images ship iptables rules that
   reject everything but 22, so the console's security list is not enough:

   ```bash
   sudo iptables -I INPUT 6 -p tcp --dport 8477 -j ACCEPT
   sudo netfilter-persistent save
   ```

   Then add an ingress rule for TCP 8477 in the subnet's security list or NSG.
3. **Install Docker** and put the login in the `docker` group, or the deploy's
   `docker compose` fails on permissions:

   ```bash
   curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker ubuntu
   ```

   Log out and back in for the group to take effect.
4. **Check `/api/resolve` before anything else.** This is the one step that can
   fail for a reason no configuration fixes.

Plain HTTP is the default on purpose: the wallpaper is a `file://` page rather
than an `https://` one, so it is not subject to mixed-content blocking and can
pull audio from an `http://` origin. If you own a domain and would rather not
stream in the clear, `docker compose --profile tls up -d` puts Caddy in front and
it obtains its own certificate - that needs `LOFI_DOMAIN` in `.env`, an A record,
and ports 80 and 443 open in both places. Let's Encrypt will not issue for a bare
IP.

### Bandwidth

One listener, measured at steady state on a warm station:

| `?q=` | Audio | HTTP requests | Total | 16 h/day, 31-day month |
|---|---|---|---|---|
| 32 (default) | 16.1 MB/h | 4.0 MB/h | 20.1 MB/h | **10.0 GB** |
| 48 | 19.4 | 4.0 | 23.4 | 11.6 GB |
| 96 | ~37 | 4.0 | ~41 | 20.3 GB |

The request line is not noise: ffmpeg issues about 2,340 requests an hour, each
carrying a googlevideo URL around 1,241 characters long. The ~93 GB/month of
segments coming *down* is inbound, which hosts generally do not bill.

That rules out several free tiers. Render's Hobby workspace includes 5 GB of
outbound a month, which is about 8 hours of listening a day. Oracle Cloud's
Always Free includes 10 TB globally, which this does not come close to.

## Upkeep: the YouTube client version

`YoutubeResolver.CLIENT_VERSION` is the one value here that expires. Measured on
2026-09-18, against a live station, holding everything else fixed:

| `clientVersion` | |
|---|---|
| major ≤ 19 (e.g. `19.09.37`) | **400** - too old, dropped |
| major 20 and 21, minor 01-41 | **200** |
| above `21.41`, or major ≥ 22 | **404** `Requested entity was not found` |
| any minor `00` | **404** |

So the accepted window has both a floor and a ceiling, and no value survives
indefinitely - `99.99.99` is a 404, not a shortcut. The third field is ignored
entirely (`21.02.00`, `21.02.99` and `21.02.999` all answer 200).

The version reads as `year.month.build`, so `21.02.35` is February 2026 and the
floor currently sits exactly at a major boundary, one major behind the current
one. If that pattern holds, the shipped value stops working when major 23 ships,
around January 2028. **400 means bump it; 404 means the value is too high.**

The wallpaper's own station list has a separate `clientVersion` for the `WEB`
client, which is only a date and needs no upkeep - it is built from the clock at
runtime. That lives in lofi-wallpaper.

## Installing the wallpaper

`--install` registers the wallpaper with Wallpaper Engine and still lives in this
jar. It finds the wallpaper by walking up from the jar, then up from the working
directory, looking for a folder with `project.json` and `index.html` in it. Now
that the two repos are separate, only the second of those finds anything, so run
it from the wallpaper's folder:

```bash
cd ../lofi-wallpaper
java -jar ../lofi-server/target/lofi-server.jar --install
```

`--install --link` makes a junction instead, for working on the wallpaper.
`--uninstall` removes whichever of the two is there.
