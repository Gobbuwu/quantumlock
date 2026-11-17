# Dashboard (task 2.3, KAN-13, Frontend / Dashboard)

Create the React app in this folder. During development it calls the backend at `http://localhost:8080`.
The available endpoints are listed in the Swagger UI at `http://localhost:8080/swagger-ui.html` while the backend is running.

Two things to know before the first call from the browser (details in [SECURITY.md](../SECURITY.md)):

- The backend does not allow other origins yet, so the browser will block calls from the React dev server
  until the backend allows exactly that origin (for example `http://localhost:5173`). Never use a wildcard `*`.
- Never put an API key in the React source code or in a file that is committed. Use a local `.env` file, which git ignores.
