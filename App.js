import 'react-native-url-polyfill/auto';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator, Alert, FlatList, Image, SafeAreaView, StatusBar, StyleSheet,
  Text, TextInput, TouchableOpacity, View,
} from 'react-native';
import { Audio } from 'expo-av';
import { createClient } from '@supabase/supabase-js';

const URL = process.env.EXPO_PUBLIC_SUPABASE_URL || '';
const KEY = process.env.EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY || '';
const supabase = URL && KEY ? createClient(URL, KEY) : null;
const COLORS = { bg: '#111315', panel: '#181B1E', raised: '#1F2327', line: '#2A2F34', text: '#FAF9F6', muted: '#A6ACB1', dim: '#6B7279' };
const formatTime = (seconds = 0) => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;

export default function App() {
  const [tracks, setTracks] = useState([]);
  const [loading, setLoading] = useState(Boolean(supabase));
  const [query, setQuery] = useState('');
  const [current, setCurrent] = useState(null);
  const [playing, setPlaying] = useState(false);
  const [sound, setSound] = useState(null);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);

  const loadTracks = useCallback(async () => {
    if (!supabase) { setLoading(false); return; }
    setLoading(true);
    const { data, error } = await supabase.from('tracks')
      .select('id,title,user_id,audio_url,audio_path,cover_url,cover_path,duration,created_at,profiles(display_name)')
      .order('created_at', { ascending: false });
    if (error) Alert.alert('Could not load music', `${error.message}\nCheck your Supabase setup and policies.`);
    else {
      setTracks((data || []).map(track => ({
        ...track,
        audioUrl: track.audio_url || (track.audio_path ? supabase.storage.from('audio').getPublicUrl(track.audio_path).data.publicUrl : null),
        coverUrl: track.cover_url || (track.cover_path ? supabase.storage.from('artwork').getPublicUrl(track.cover_path).data.publicUrl : null),
      })));
    }
    setLoading(false);
  }, []);

  useEffect(() => { loadTracks(); return () => { if (sound) sound.unloadAsync(); }; }, [loadTracks]);
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? tracks.filter(t => `${t.title} ${t.profiles?.display_name || ''}`.toLowerCase().includes(q)) : tracks;
  }, [tracks, query]);

  async function toggleTrack(track) {
    if (!track?.audioUrl) return;
    if (current?.id === track.id && sound) {
      const status = await sound.getStatusAsync();
      if (status.isPlaying) { await sound.pauseAsync(); setPlaying(false); }
      else { await sound.playAsync(); setPlaying(true); }
      return;
    }
    if (sound) await sound.unloadAsync();
    setCurrent(track); setPosition(0); setDuration(0);
    const { sound: nextSound, status } = await Audio.Sound.createAsync(
      { uri: track.audioUrl }, { shouldPlay: true }, update => {
        if (update.isLoaded) {
          setPosition(update.positionMillis / 1000);
          setDuration(update.durationMillis ? update.durationMillis / 1000 : 0);
          setPlaying(update.isPlaying);
          if (update.didJustFinish) setPlaying(false);
        }
      },
    );
    setSound(nextSound); setPlaying(status.isPlaying);
  }

  const renderTrack = ({ item, index }) => (
    <TouchableOpacity accessibilityRole="button" onPress={() => toggleTrack(item)} style={styles.trackRow}>
      <View style={styles.artwork}>
        {item.coverUrl ? <Image source={{ uri: item.coverUrl }} style={styles.cover} /> : <Text style={styles.artGlyph}>♫</Text>}
      </View>
      <View style={styles.trackCopy}>
        <Text numberOfLines={1} style={styles.trackTitle}>{item.title || 'Untitled'}</Text>
        <Text numberOfLines={1} style={styles.artist}>{item.profiles?.display_name || 'Independent artist'}</Text>
      </View>
      <Text style={styles.trackTime}>{formatTime(item.duration)}</Text>
      <Text style={styles.playGlyph}>{current?.id === item.id && playing ? 'Ⅱ' : '▶'}</Text>
    </TouchableOpacity>
  );

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar barStyle="light-content" backgroundColor={COLORS.bg} />
      <FlatList
        data={filtered} keyExtractor={item => String(item.id)} renderItem={renderTrack}
        contentContainerStyle={[styles.content, current && styles.withPlayer]}
        ListHeaderComponent={(
          <>
            <View style={styles.header}><Text style={styles.logo}>JaMusic</Text><Text style={styles.headerNote}>YOUR MUSIC, INDEPENDENTLY</Text></View>
            <View style={styles.hero}>
              <Text style={styles.eyebrow}>A HOME FOR THE SOUND YOU MAKE</Text>
              <Text style={styles.headline}>Listen <Text style={styles.italic}>closer.</Text></Text>
              <Text style={styles.description}>Discover music made by people who put themselves into every note.</Text>
            </View>
            <View style={styles.sectionHead}>
              <View><Text style={styles.sectionTitle}>Discover</Text><Text style={styles.sectionSub}>Fresh sounds from independent artists</Text></View>
              <TouchableOpacity onPress={loadTracks}><Text style={styles.refresh}>↻</Text></TouchableOpacity>
            </View>
            <View style={styles.searchBox}><Text style={styles.searchIcon}>⌕</Text><TextInput value={query} onChangeText={setQuery} placeholder="Search tracks or artists" placeholderTextColor={COLORS.dim} style={styles.searchInput} returnKeyType="search" /></View>
            {!supabase && <View style={styles.notice}><Text style={styles.noticeTitle}>Connect your Supabase project</Text><Text style={styles.noticeText}>Add EXPO_PUBLIC_SUPABASE_URL and EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY to .env, then restart Expo.</Text></View>}
            {loading && <ActivityIndicator color={COLORS.text} style={{ marginVertical: 28 }} />}
            {!loading && supabase && filtered.length === 0 && <Text style={styles.empty}>{query ? 'No matches. Try another search.' : 'No tracks yet. Your next favorite might be the first one.'}</Text>}
          </>
        )}
        ListFooterComponent={<View style={styles.footer}><Text style={styles.footerBrand}>JaMusic</Text><Text style={styles.footerCopy}>Independent music deserves to be heard.</Text></View>}
      />
      {current && <View style={styles.player}>
        {current.coverUrl ? <Image source={{ uri: current.coverUrl }} style={styles.playerCover} /> : <View style={[styles.playerCover, styles.artwork]}><Text style={styles.artGlyph}>♫</Text></View>}
        <View style={styles.playerCopy}><Text numberOfLines={1} style={styles.trackTitle}>{current.title}</Text><Text numberOfLines={1} style={styles.artist}>{current.profiles?.display_name || 'Independent artist'}</Text><View style={styles.progressTrack}><View style={[styles.progress, { width: `${duration ? Math.min(position / duration, 1) * 100 : 0}%` }]} /></View></View>
        <TouchableOpacity accessibilityLabel={playing ? 'Pause' : 'Play'} onPress={() => toggleTrack(current)} style={styles.playerButton}><Text style={styles.playerButtonText}>{playing ? 'Ⅱ' : '▶'}</Text></TouchableOpacity>
      </View>}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: COLORS.bg }, content: { paddingHorizontal: 22, paddingBottom: 40 }, withPlayer: { paddingBottom: 115 },
  header: { paddingTop: 12, paddingBottom: 20, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }, logo: { color: COLORS.text, fontSize: 21, fontWeight: '700', letterSpacing: -1 }, headerNote: { color: COLORS.dim, fontSize: 9, letterSpacing: 1.5 },
  hero: { paddingTop: 52, paddingBottom: 48 }, eyebrow: { color: COLORS.muted, fontSize: 10, letterSpacing: 1.8, marginBottom: 17 }, headline: { color: COLORS.text, fontSize: 54, fontWeight: '600', letterSpacing: -2.8, lineHeight: 60 }, italic: { fontStyle: 'italic', fontWeight: '400' }, description: { color: COLORS.muted, fontSize: 16, lineHeight: 25, maxWidth: 320, marginTop: 18 },
  sectionHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: 18 }, sectionTitle: { color: COLORS.text, fontSize: 29, fontWeight: '500', letterSpacing: -1 }, sectionSub: { color: COLORS.dim, fontSize: 12, marginTop: 5 }, refresh: { color: COLORS.muted, fontSize: 25, paddingHorizontal: 8 },
  searchBox: { height: 48, borderRadius: 24, backgroundColor: COLORS.raised, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, marginBottom: 18 }, searchIcon: { color: COLORS.muted, fontSize: 24, marginRight: 8 }, searchInput: { flex: 1, color: COLORS.text, fontSize: 14 },
  notice: { backgroundColor: COLORS.panel, borderWidth: 1, borderColor: COLORS.line, borderRadius: 12, padding: 16, marginVertical: 14 }, noticeTitle: { color: COLORS.text, fontSize: 15, fontWeight: '600' }, noticeText: { color: COLORS.muted, fontSize: 13, lineHeight: 20, marginTop: 5 }, empty: { color: COLORS.muted, fontSize: 14, paddingVertical: 28, lineHeight: 21 },
  trackRow: { minHeight: 76, flexDirection: 'row', alignItems: 'center', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: COLORS.line }, artwork: { width: 52, height: 52, borderRadius: 5, backgroundColor: COLORS.raised, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }, cover: { width: '100%', height: '100%' }, artGlyph: { color: COLORS.muted, fontSize: 22 }, trackCopy: { flex: 1, marginLeft: 13, marginRight: 10 }, trackTitle: { color: COLORS.text, fontSize: 14, fontWeight: '500' }, artist: { color: COLORS.muted, fontSize: 12, marginTop: 4 }, trackTime: { color: COLORS.dim, fontSize: 11, marginRight: 14 }, playGlyph: { color: COLORS.text, width: 24, textAlign: 'center', fontSize: 13 },
  footer: { paddingTop: 48, paddingBottom: 24 }, footerBrand: { color: COLORS.text, fontSize: 22, fontWeight: '600' }, footerCopy: { color: COLORS.dim, fontSize: 12, marginTop: 5 },
  player: { position: 'absolute', bottom: 0, left: 0, right: 0, minHeight: 78, backgroundColor: COLORS.panel, borderTopColor: COLORS.line, borderTopWidth: 1, paddingHorizontal: 16, paddingVertical: 12, flexDirection: 'row', alignItems: 'center' }, playerCover: { width: 48, height: 48, borderRadius: 4 }, playerCopy: { flex: 1, marginHorizontal: 12 }, progressTrack: { height: 2, backgroundColor: COLORS.line, marginTop: 9, borderRadius: 2, overflow: 'hidden' }, progress: { height: '100%', backgroundColor: COLORS.text }, playerButton: { width: 42, height: 42, backgroundColor: COLORS.text, borderRadius: 22, alignItems: 'center', justifyContent: 'center' }, playerButtonText: { color: COLORS.bg, fontSize: 14 },
});
