# Dashboard (tasks 2.3, 4.1 and 4.2)

React page where a team generates post-quantum key pairs and sees its keys. It follows the Key Management
wireframe in the RRD. Built with Vite, React 19 and Vitest.

## Run it

You need Node.js 22 or newer. From this folder:

```bash
npm install
npm run dev
```

Open http://localhost:5173. The page calls the backend through Vite, so start the backend first
(see the main README). If the backend is not running, the page says so and offers to try again.

The page first asks for an API key. Issue one with the backend's `issue-api-key` command (main README, section 3)
and paste it in. The page checks it with the API before showing any keys. If the key stops working (for example
because it was revoked), the page asks for a key again. **Forget API key** in the header removes it.

To work on the page without the backend, use made-up data instead:

```bash
npm run dev:mock
```

## Test and build

```bash
npm test          # Vitest: the API client, the key list and the generate form
npm run build     # production files in dist/
npm run preview   # serves dist/ on http://127.0.0.1:4173 with the content security policy
```

GitHub Actions runs both on every pull request (`.github/workflows/dashboard-ci.yml`).

## How it talks to the backend

The browser only calls `/api/...` on the dashboard's own address. Vite forwards those calls to
`http://127.0.0.1:8080` (see `vite.config.js`), so the backend needs no CORS setting and both stay on this
computer. All calls go through `src/api/client.js`. `src/api/mock.js` returns the same shapes with made-up data.

## How it looks (KAN-28)

A dark graphite theme (`src/styles.css`). Colour sits only on the data: each key pair has a small lattice glyph
drawn from its key ID (`src/components/lattice.js`), blue for ML-KEM, rose for ML-DSA and grey once the key is
rotated or revoked. The same ID always draws the same glyph, so keys are easy to tell apart. The glyph is not a
fingerprint and does not verify a key. A key you just generated settles into its glyph once; with reduced motion
turned on, nothing moves. The key list turns into two-line rows when its panel is narrow (a container query), so
the page never scrolls sideways, down to 320 px. Fonts: Schibsted Grotesk and Martian Mono, bundled from npm.

## Security notes

- The page never receives a private key: the API returns only the key ID, algorithm, status, public key and time.
- The API key is typed into a password field and kept in memory only. If you tick "Remember until this browser tab
  is closed", it is also kept in the tab's `sessionStorage`, which the browser deletes when the tab closes. It is
  never put in `localStorage`, a cookie, the URL, the source code or a committed file.
- The key travels in the `X-API-Key` header, through Vite's proxy on this computer only.
- The production build (`npm run build`, then `npm run preview`) is served with a strict content security policy
  and other security headers, set in `vite.config.js`: script, styles, fonts, the icon and the `/api` calls may
  only come from the dashboard's own address, no inline script runs and no other site may frame the page. The
  end-to-end test runs against this build and fails on any policy violation. `npm run dev` has no policy, because
  Vite's development server needs inline scripts. To serve `dist/` with another web server, send the same headers.
- `.npmrc` sets `legacy-peer-deps=true` because npm 10 crashes on Vitest 5's optional peer dependencies.
  Every peer this project needs is listed in `package.json`.
