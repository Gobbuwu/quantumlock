import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource/instrument-sans/400.css'
import '@fontsource/instrument-sans/500.css'
import '@fontsource/instrument-sans/600.css'
import '@fontsource/ibm-plex-mono/400.css'
import './styles.css'
import App from './App.jsx'
import { api } from './api/index.js'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App api={api} />
  </StrictMode>,
)
