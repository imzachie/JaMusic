let transcriberPromise;
function getTranscriber(onProgress) {
  if (!transcriberPromise) {
    transcriberPromise = import('@huggingface/transformers').then(({ env, pipeline }) => {
      // The model runs locally in the visitor's browser; no inference API is used.
      env.allowLocalModels = false;
      env.allowRemoteModels = true;
      env.useBrowserCache = true;
      env.backends.onnx.wasm.proxy = true;
      env.backends.onnx.wasm.numThreads = 1;
      return pipeline(
        'automatic-speech-recognition',
        'onnx-community/whisper-tiny.en',
        {
          device: 'wasm',
          dtype: 'q8',
          progress_callback: progress => {
            if (progress.status === 'progress' && progress.file && Number.isFinite(progress.progress)) {
              onProgress?.(`Downloading Whisper model: ${Math.round(progress.progress)}%`);
            } else if (progress.status === 'done') {
              onProgress?.('Whisper model ready. Transcribing on this device…');
            }
          },
        },
      );
    }).catch(error => {
      transcriberPromise = null;
      throw error;
    });
  }
  return transcriberPromise;
}

async function decodeAt16k(file) {
  const AudioContextClass = window.AudioContext || window.webkitAudioContext;
  if (!AudioContextClass) throw new Error('This browser does not support local audio transcription. Try a current version of Chrome, Edge, or Firefox.');
  const context = new AudioContextClass();
  try {
    const buffer = await context.decodeAudioData(await file.arrayBuffer());
    const sourceRate = buffer.sampleRate;
    const targetRate = 16000;
    const outputLength = Math.floor(buffer.length * targetRate / sourceRate);
    const channels = Array.from({ length:buffer.numberOfChannels }, (_, index) => buffer.getChannelData(index));
    const output = new Float32Array(outputLength);
    for (let i = 0; i < outputLength; i++) {
      const sourcePosition = i * sourceRate / targetRate;
      const left = Math.floor(sourcePosition);
      const fraction = sourcePosition - left;
      let sample = 0;
      for (const channel of channels) {
        const a = channel[left] || 0;
        const b = channel[Math.min(left + 1, channel.length - 1)] || 0;
        sample += a + (b - a) * fraction;
      }
      output[i] = sample / channels.length;
    }
    return output;
  } finally {
    await context.close();
  }
}

export async function transcribeLyrics(file, onProgress) {
  onProgress?.('Loading the free Whisper model…');
  const transcriber = await getTranscriber(onProgress);
  onProgress?.('Preparing audio…');
  const audio = await decodeAt16k(file);
  onProgress?.('Transcribing on this device…');
  const result = await transcriber(
    { data:audio, sampling_rate:16000 },
    { chunk_length_s:30, stride_length_s:5, return_timestamps:true },
  );
  const chunks = Array.isArray(result.chunks) ? result.chunks : [];
  const lines = chunks
    .filter(chunk => typeof chunk.text === 'string' && chunk.text.trim())
    .map(chunk => {
      const start = Array.isArray(chunk.timestamp) && Number.isFinite(chunk.timestamp[0]) ? chunk.timestamp[0] : 0;
      const seconds = Math.max(0, Math.floor(start));
      return `[${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}] ${chunk.text.trim()}`;
    });
  if (!lines.length && typeof result.text === 'string' && result.text.trim()) lines.push(`[0:00] ${result.text.trim()}`);
  if (!lines.length) throw new Error('Whisper did not detect speech. Choose Manual lyrics typing or Instrumental instead.');
  return lines.join('\n');
}

window.transcribeLyrics = transcribeLyrics;
