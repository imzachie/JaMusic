import { load, JSON_SCHEMA } from 'js-yaml';

const centi = seconds => Math.max(0, Math.round(Number(seconds) * 100));
const stamp = value => {
  let cs;
  if (typeof value === 'string' && /^\d{1,3}:\d{2}(?:\.\d{1,3})?$/.test(value)) {
    const [min, rest] = value.split(':');
    const [sec, fraction = '0'] = rest.split('.');
    cs = Number(min) * 6000 + Number(sec) * 100 + Number((fraction + '00').slice(0, 2));
  } else cs = centi(value);
  const mm = Math.floor(cs / 6000), ss = Math.floor(cs / 100) % 60, hh = cs % 100;
  return `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}.${String(hh).padStart(2, '0')}`;
};

function parseLrc(source) {
  const output = [];
  for (const raw of source.split(/\r?\n/)) {
    // Standard and enhanced LRC: [line cue]<word cue>word<word cue>word
    const lineMatch = raw.match(/^\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\](.*)$/);
    if (!lineMatch) continue; // Ignore [ti:], [ar:], and other metadata tags.
    const fraction = lineMatch[3] || '0';
    const time = Number(lineMatch[1]) * 60 + Number(lineMatch[2]) + Number((fraction + '00').slice(0, 2)) / 100;
    const rest = lineMatch[4].trim();
    if (!rest) continue;
    const enhanced = rest.match(/<\d{1,3}:\d{2}\.\d{1,3}>/);
    let body = rest;
    if (enhanced) {
      body = rest.replace(/<(\d{1,3}):(\d{2})\.(\d{1,3})>/g, (_, mm, ss, hh) => `<${stamp(Number(mm) * 60 + Number(ss) + Number((hh + '00').slice(0, 2)) / 100)}>`);
    }
    output.push(`[${stamp(time)}]${body.startsWith('<') ? '' : ' '}${body}`);
  }
  if (!output.length) throw new Error('No timestamped lyric lines found in that LRC file.');
  return output.join('\n');
}

function yamlToLyrics(source) {
  // Accept serialized notepad YAML, a YAML lyrics string, arrays of timed lines,
  // and the common { lines: [...] } / { segments: [...] } shapes.
  let normalized = String(source).trim().replace(/\\_/g, '_');
  if ((normalized.startsWith('"') && normalized.endsWith('"')) || (normalized.startsWith("'") && normalized.endsWith("'"))) normalized = normalized.slice(1, -1);
  let data = load(normalized, { schema: JSON_SCHEMA });
  if (typeof data === 'string') {
    const inner = data.trim();
    if (!inner) throw new Error('The YAML lyrics file is empty.');
    try {
      const nested = load(inner, { schema: JSON_SCHEMA });
      if (Array.isArray(nested) || Array.isArray(nested?.lines) || Array.isArray(nested?.segments)) data = nested;
      else return inner;
    } catch { return inner; }
  }
  if (data && !Array.isArray(data)) {
    const lyrics = data.lyrics;
    if (Array.isArray(lyrics)) data = lyrics;
    else if (typeof lyrics === 'string') {
      const inner = lyrics.trim();
      try {
        const nested = load(inner, { schema: JSON_SCHEMA });
        if (Array.isArray(nested) || Array.isArray(nested?.lines) || Array.isArray(nested?.segments)) data = nested;
        else return inner;
      } catch { return inner; }
    }
  }
  const lines = Array.isArray(data) ? data : Array.isArray(data?.lines) ? data.lines : Array.isArray(data?.segments) ? data.segments : null;
  if (!lines?.length) throw new Error('YAML must contain lyrics text or a list of timed lines.');
  if (lines.every(line => typeof line === 'string')) return lines.join('\n');
  const result = lines.map((line, index) => {
    const rawTime = line?.start_ms != null ? Number(line.start_ms) / 1000 : (line?.time ?? line?.timestamp);
    const validTime = Number.isFinite(Number(rawTime)) || (typeof rawTime === 'string' && /^\d{1,3}:\d{2}(?:\.\d{1,3})?$/.test(rawTime));
    if (!line || typeof line !== 'object' || !validTime) throw new Error(`YAML line ${index + 1} needs start_ms (milliseconds), time (seconds), or an MM:SS.hh timestamp.`);
    const time = stamp(rawTime);
    if (Array.isArray(line.words) && line.words.length) {
      const words = line.words.map((word, wi) => {
        const rawWordTime = word?.start_ms != null ? Number(word.start_ms) / 1000 : word?.time;
        if (!word || typeof word.text !== 'string' || !word.text.trim() || !Number.isFinite(Number(rawWordTime))) throw new Error(`Word ${wi + 1} on YAML line ${index + 1} needs text and start_ms (milliseconds) or time (seconds).`);
        return `<${stamp(rawWordTime)}>${word.text}`;
      }).join('');
      return `[${time}]${words}`;
    }
    if (typeof line.text !== 'string' || !line.text.trim()) throw new Error(`YAML line ${index + 1} needs text, or a words list.`);
    return `[${time}] ${line.text.trim()}`;
  });
  return result.join('\n');
}

window.importLyricsFile = (name, source) => {
  const ext = name.toLowerCase().split('.').pop();
  if (ext === 'lrc') return parseLrc(source);
  if (ext === 'yaml' || ext === 'yml') return yamlToLyrics(source);
  throw new Error('Choose an .lrc, .yaml, or .yml lyrics file.');
};

const lyricsTextArea = document.querySelector('#lyricsNotepadText');
const lyricsStatus = document.querySelector('#lyricsNotepadStatus');
document.querySelector('#lyricsPaste')?.addEventListener('click', async () => {
  try {
    const copied = await navigator.clipboard.readText();
    if (!copied) {
      lyricsStatus.textContent = 'Clipboard is empty. Copy lyrics from LRCLIB, then try again.';
      return;
    }
    const start = lyricsTextArea.selectionStart;
    const end = lyricsTextArea.selectionEnd;
    lyricsTextArea.setRangeText(copied, start, end, 'end');
    lyricsTextArea.dispatchEvent(new Event('input', { bubbles: true }));
    lyricsTextArea.focus();
    lyricsStatus.textContent = 'Pasted lyrics from clipboard.';
  } catch {
    lyricsTextArea.focus();
    lyricsStatus.textContent = 'Clipboard access was blocked. Click in the notepad and press Ctrl+V (or Cmd+V).';
  }
});
document.querySelector('#lyricsCopy')?.addEventListener('click', async () => {
  const text = lyricsTextArea?.value || '';
  if (!text) {
    lyricsStatus.textContent = 'There is no notepad text to copy yet.';
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    lyricsStatus.textContent = 'Copied notepad text to clipboard.';
  } catch {
    lyricsTextArea.focus();
    lyricsTextArea.select();
    lyricsStatus.textContent = 'Clipboard access was blocked. The text is selected; press Ctrl+C (or Cmd+C).';
  }
});

const notepadSave = document.querySelector('#lyricsNotepadSave');
notepadSave?.addEventListener('click', () => {
  const text = document.querySelector('#lyricsNotepadText')?.value || '';
  const nameInput = document.querySelector('#lyricsNotepadFilename');
  const format = document.querySelector('#lyricsNotepadFormat')?.value || 'txt';
  const status = document.querySelector('#lyricsNotepadStatus');
  const baseName = (nameInput?.value || 'lyrics').trim().replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'lyrics';
  if (!text.trim()) {
    status.textContent = 'Type or paste some lyrics before saving.';
    return;
  }
  if (format === 'pdf') {
    const printWindow = window.open('', '_blank');
    if (!printWindow) {
      status.textContent = 'Allow pop-ups for JaMusic to print or save a PDF.';
      return;
    }
    printWindow.document.open();
    printWindow.document.write('<!doctype html><html><head><meta charset="utf-8"><title>Lyrics</title><style>body{font:12pt/1.6 Arial,sans-serif;color:#111;margin:24mm}h1{font-size:18pt;margin:0 0 16pt}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:11pt/1.6 Arial,sans-serif}</style></head><body><h1></h1><pre></pre></body></html>');
    printWindow.document.close();
    printWindow.document.title = `${baseName}.pdf`;
    printWindow.document.querySelector('h1').textContent = baseName;
    printWindow.document.querySelector('pre').textContent = text;
    printWindow.focus();
    printWindow.setTimeout(() => printWindow.print(), 250);
    status.textContent = 'In the print dialog, choose “Save as PDF” to download your file.';
    return;
  }
  const content = format === 'yaml' || format === 'yml' ? `lyrics: ${JSON.stringify(text)}\n` : text;
  const mimeType = ['txt', 'lrc'].includes(format) ? 'text/plain;charset=utf-8' : 'application/yaml;charset=utf-8';
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${baseName}.${format}`;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  status.textContent = `Downloaded ${baseName}.${format}`;
});
