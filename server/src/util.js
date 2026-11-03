import { Readable } from 'node:stream';

export const ah = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

/** Pipe a fetch Response to an express response, copying useful headers. */
export function pipeFetch(upstream, res, { headers = ['content-type', 'content-length', 'content-range', 'accept-ranges', 'last-modified', 'etag', 'cache-control'], extra = {} } = {}) {
  res.status(upstream.status);
  for (const h of headers) {
    const v = upstream.headers.get(h);
    if (v) res.setHeader(h, v);
  }
  for (const [k, v] of Object.entries(extra)) res.setHeader(k, v);
  if (!upstream.body) return res.end();
  const body = Readable.fromWeb(upstream.body);
  body.on('error', () => res.destroy());
  res.on('close', () => body.destroy());
  body.pipe(res);
}

export function srtToVtt(srt) {
  return 'WEBVTT\n\n' + srt.replace(/\r/g, '').replace(/^﻿/, '')
    .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2')
    .replace(/^\d+\n(?=\d{2}:\d{2})/gm, '');
}
