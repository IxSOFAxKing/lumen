// Which audio / subtitle track plays, and when the original file can play as it is.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { preferredTracks, selectedTracks, canPlayOriginal, isExtraAudio } from '../src/services/playdecide.js';

const A = (id, extra = {}) => ({ id, streamType: 2, codec: 'eac3', languageCode: 'eng', ...extra });
const S = (id, extra = {}) => ({ id, streamType: 3, codec: 'srt', key: `/library/streams/${id}`, languageCode: 'eng', ...extra });

test('commentary tracks are recognised', () => {
  assert.equal(isExtraAudio({ displayTitle: "Director's Commentary" }), true);
  assert.equal(isExtraAudio({ title: 'Audio Description', displayTitle: 'English (AAC)' }), true);
  assert.equal(isExtraAudio({ displayTitle: 'English (EAC3 5.1)' }), false);
});

test('the preferred language never picks a commentary', () => {
  const streams = [A(1, { displayTitle: 'Commentary', selected: true }), A(2, { displayTitle: 'English (EAC3 5.1)' })];
  assert.equal(preferredTracks(streams, { audioLang: 'en' }).audioStreamID, 2);
});

test('a track that already fits the preferred language is kept', () => {
  const streams = [A(1, { selected: true }), A(2, { languageCode: 'spa' })];
  assert.equal(preferredTracks(streams, { audioLang: 'en' }).audioStreamID, undefined);
});

test('a track picked by hand wins over preferences', () => {
  const streams = [A(1, { displayTitle: 'Commentary' }), A(2)];
  assert.equal(preferredTracks(streams, { audioLang: 'en' }, { audioStreamID: 1 }).audioStreamID, 1);
});

test('with nothing selected, the main track plays rather than a commentary', () => {
  const streams = [A(1, { displayTitle: 'Commentary' }), A(2)];
  assert.equal(selectedTracks(streams).selAudio.id, 2);
});

test('subtitles "always" picks the subtitle language, not forced ones', () => {
  const streams = [A(1, { selected: true }), S(5, { forced: true }), S(6), S(7, { languageCode: 'fre' })];
  assert.equal(preferredTracks(streams, { subtitleMode: 'always', subtitleLang: 'en' }).subtitleStreamID, 6);
  assert.equal(preferredTracks(streams, { subtitleMode: 'always', subtitleLang: 'fr' }).subtitleStreamID, 7);
});

test('subtitles "foreign" only for foreign-language audio (forced ones otherwise)', () => {
  const eng = [A(1, { selected: true }), S(5, { forced: true }), S(6)];
  assert.equal(preferredTracks(eng, { subtitleMode: 'foreign', subtitleLang: 'en' }).subtitleStreamID, 5);
  const jpn = [A(1, { selected: true, languageCode: 'jpn' }), S(5, { forced: true }), S(6)];
  assert.equal(preferredTracks(jpn, { subtitleMode: 'foreign', subtitleLang: 'en' }).subtitleStreamID, 6);
});

test('the app plays the original only when the device plays its sound and picture', () => {
  const media = { container: 'mkv', videoCodec: 'hevc', audioCodec: 'truehd' };
  const streams = [A(1, { codec: 'truehd', selected: true })];
  const selAudio = streams[0];
  assert.equal(canPlayOriginal({ native: true, media, streams, selAudio }), true);
  assert.equal(canPlayOriginal({ native: true, media, streams, selAudio, audioCodecs: ['aac', 'ac3'] }), false);
  assert.equal(canPlayOriginal({ native: true, media, streams, selAudio, audioCodecs: ['truehd'], videoCodecs: ['h264'] }), false);
  assert.equal(canPlayOriginal({ native: true, media, streams, selAudio, audioCodecs: ['truehd'], videoCodecs: ['hevc'] }), true);
});

test('browsers play the original only for simple files (first audio track, text subtitles)', () => {
  const media = { container: 'mp4', videoCodec: 'h264', audioCodec: 'aac' };
  const streams = [A(1, { codec: 'aac' }), A(2, { codec: 'aac' }), S(5, { codec: 'pgs', key: null })];
  assert.equal(canPlayOriginal({ native: false, media, streams, selAudio: streams[0], selSub: null }), true);
  assert.equal(canPlayOriginal({ native: false, media, streams, selAudio: streams[1], selSub: null }), false);
  assert.equal(canPlayOriginal({ native: false, media, streams, selAudio: streams[0], selSub: streams[2] }), false);
  assert.equal(canPlayOriginal({ native: false, media: { ...media, container: 'mkv' }, streams, selAudio: streams[0] }), false);
});
