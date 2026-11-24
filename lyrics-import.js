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
    const lineMatch = raw.match(/^\[(\d{1,3}):(\d{2})\.(\d{1,3})\](.*)$/);
    if (!lineMatch) continue; // Ignore [ti:], [ar:], and other metadata tags.
    const time = Number(lineMatch[1]) * 60 + Number(lineMatch[2]) + Number((lineMatch[3] + '00').slice(0, 2)) / 100;
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
  // Accept escaped underscores and a whole-document quote wrapper from copied YAML snippets.
  let normalized = String(source).trim().replace(/\\_/g, '_');
  if ((normalized.startsWith('"') && normalized.endsWith('"')) || (normalized.startsWith("'") && normalized.endsWith("'"))) normalized = normalized.slice(1, -1);
  const data = load(normalized, { schema: JSON_SCHEMA });
  if (!data || !Array.isArray(data.lines) || !data.lines.length) throw new Error('YAML needs a top-level “lines” list. See the example below.');
  const result = data.lines.map((line, index) => {
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
