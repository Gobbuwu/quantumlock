import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// Fonts are bundled from npm and served from the dashboard's own address (CSP font-src 'self').
import '@fontsource-variable/schibsted-grotesk/wght.css'
import '@fontsource/martian-mono/400.css'
import './styles.css'
import App from './App.jsx'
import { api, keyStore } from './api/index.js'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App api={api} keyStore={keyStore} />
  </StrictMode>,
)
