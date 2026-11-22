# Dashboard (tasks 2.3 and 4.1)

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

To work on the page without the backend, use made-up data instead:

```bash
npm run dev:mock
```

## Test and build

```bash
npm test          # Vitest: the API client, the key list and the generate form
npm run build     # production files in dist/
```

GitHub Actions runs both on every pull request (`.github/workflows/dashboard-ci.yml`).

## How it talks to the backend

The browser only calls `/api/...` on the dashboard's own address. Vite forwards those calls to
`http://127.0.0.1:8080` (see `vite.config.js`), so the backend needs no CORS setting and both stay on this
computer. All calls go through `src/api/client.js`. `src/api/mock.js` returns the same shapes with made-up data.

## Security notes

- The page never receives a private key: the API returns only the key ID, algorithm, status, public key and time.
- Never put an API key in the source code or in a committed file. Use a local `.env.local` file, which git ignores.
- `.npmrc` sets `legacy-peer-deps=true` because npm 10 crashes on Vitest 5's optional peer dependencies.
  Every peer this project needs is listed in `package.json`.
