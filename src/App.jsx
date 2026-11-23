import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createClient } from '@supabase/supabase-js';

const url = import.meta.env.VITE_SUPABASE_URL;
const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY;
const supabase = url && key ? createClient(url, key) : null;
const fmt = value => `${Math.floor((value || 0) / 60)}:${String(Math.floor((value || 0) % 60)).padStart(2, '0')}`;

function App() {
  const [tracks, setTracks] = useState([]);
  const [query, setQuery] = useState('');
  const [current, setCurrent] = useState(null);
  const [playing, setPlaying] = useState(false);
  const [loading, setLoading] = useState(Boolean(supabase));
  const [error, setError] = useState('');
  const [progress, setProgress] = useState(0);
  const [duration, setDuration] = useState(0);
  const audio = useRef(null);

  const load = useCallback(async () => {
    if (!supabase) { setLoading(false); return; }
    setLoading(true); setError('');
    const { data, error: loadError } = await supabase.from('tracks')
      .select('*,profiles(display_name,username,avatar_url)')
      .order('created_at', { ascending: false }).limit(100);
    if (loadError) setError(`Could not load tracks: ${loadError.message}`);
    else setTracks(data || []);
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);
  const visibleTracks = useMemo(() => {
    const term = query.trim().toLowerCase();
    return tracks.filter(track => !term || [track.title, track.album, track.genre, track.profiles?.display_name].join(' ').toLowerCase().includes(term));
  }, [tracks, query]);

  function playTrack(track) {
    if (!track.audio_url) return;
    if (current?.id === track.id && audio.current) {
      if (audio.current.paused) { audio.current.play(); setPlaying(true); }
      else { audio.current.pause(); setPlaying(false); }
      return;
    }
    setCurrent(track); setProgress(0);
    if (audio.current) { audio.current.src = track.audio_url; audio.current.play().then(() => setPlaying(true)).catch(() => setPlaying(false)); }
  }

  function onTime() {
    if (!audio.current) return;
    setProgress(audio.current.currentTime || 0);
    setDuration(audio.current.duration || 0);
  }

  return <>
    <audio ref={audio} onTimeUpdate={onTime} onLoadedMetadata={onTime} onEnded={() => setPlaying(false)} />
    <aside className="sidebar">
      <a className="wordmark" href="#home">JaMusic<span>.</span></a>
      <div className="side-caption">YOUR MUSIC, INDEPENDENTLY</div>
      <nav><a className="nav-link active" href="#discover"><span>⌕</span> Discover</a><a className="nav-link" href="#artists"><span>◉</span> Artists</a></nav>
      <div className="sidebar-bottom"><span className="live-dot" /> Made for the music people make.</div>
    </aside>
    <main className="main" id="home">
      <header className="topbar"><span className="mobile-brand">JaMusic<span>.</span></span><label className="search"><span>⌕</span><input aria-label="Search tracks and artists" value={query} onChange={event => setQuery(event.target.value)} placeholder="Search tracks, artists, albums…" /></label><a className="top-link" href="/legacy.html">Original JaMusic</a></header>
      <section className="hero">
        <div className="hero-glow" />
        <p className="eyebrow"><span className="live-dot" /> A HOME FOR INDEPENDENT MUSIC</p>
        <h1>Listen <em>closer.</em></h1>
        <p className="hero-copy">Discover sounds made by people who put themselves into every note. No noise, just music that means something.</p>
        <a href="#discover" className="hero-cta">Explore the music <span>↓</span></a>
        <div className="hero-note">CURATED BY THE PEOPLE WHO MAKE IT</div>
      </section>
      <section className="discover" id="discover">
        <div className="section-heading"><div><p className="eyebrow">THE COMMUNITY, IN ROTATION</p><h2>Freshly found<span>.</span></h2></div><button className="refresh" onClick={load} aria-label="Refresh tracks">↻</button></div>
        {!supabase && <div className="setup-banner"><strong>Connect your Supabase project</strong><span>Set VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY in your local .env or Cloudflare Pages environment variables.</span></div>}
        {error && <div className="setup-banner error">{error}</div>}
        {loading ? <div className="empty">Loading the latest sounds…</div> : visibleTracks.length ? <div className="track-list">{visibleTracks.map((track, index) => <button className={`track ${current?.id === track.id ? 'selected' : ''}`} key={track.id} onClick={() => playTrack(track)}>
          <span className="track-index">{current?.id === track.id && playing ? <i className="bars"><b/><b/><b/></i> : String(index + 1).padStart(2, '0')}</span>
          <span className="art">{track.cover_url ? <img src={track.cover_url} alt="" /> : <span>♫</span>}</span>
          <span className="track-info"><strong>{track.title || 'Untitled'}</strong><small>{track.profiles?.display_name || 'Independent artist'}{track.album ? ` · ${track.album}` : ''}</small></span>
          <span className="genre">{track.genre || '—'}</span><span className="track-duration">{fmt(track.duration)}</span><span className="play">{current?.id === track.id && playing ? 'Ⅱ' : '▶'}</span>
        </button>)}</div> : <div className="empty">{query ? `No results for “${query}”. Try another search.` : 'Nothing in rotation just yet. Check back soon.'}</div>}
      </section>
      <footer><span className="footer-logo">JaMusic<span>.</span></span><span>Independent music deserves to be heard.</span><a href="/legacy.html">Visit the original experience ↗</a></footer>
    </main>
    {current && <aside className="now-playing"><div className="now-art">{current.cover_url ? <img src={current.cover_url} alt="" /> : '♫'}</div><div className="now-info"><b>{current.title}</b><span>{current.profiles?.display_name || 'Independent artist'}</span><div className="progress"><i style={{ width: `${duration ? progress / duration * 100 : 0}%` }} /></div></div><span className="now-time">{fmt(progress)} <small>/</small> {fmt(duration)}</span><button className="player-button" onClick={() => playTrack(current)} aria-label={playing ? 'Pause' : 'Play'}>{playing ? 'Ⅱ' : '▶'}</button></aside>}
  </>;
}

export default App;
