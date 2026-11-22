import KeysPage from './components/KeysPage.jsx'

export default function App({ api, mock = import.meta.env.VITE_API_MODE === 'mock' }) {
  return (
    <>
      <a className="skip-link" href="#main">Skip to content</a>
      <header className="masthead">
        <div className="masthead-inner">
          <span className="wordmark">QuantumLock</span>
          <h1>Key pairs</h1>
          <p className="lede">
            Generate post-quantum key pairs for your team. Private keys stay encrypted on the server and are
            never shown here.
          </p>
          {mock && (
            <p className="mock-notice">Mock mode: this page shows made-up keys and does not call the API.</p>
          )}
        </div>
      </header>
      <main id="main" className="page">
        <KeysPage api={api} />
      </main>
    </>
  )
}
