import { h, logo, nowS, fmtTime } from './ui.js';
import { ui } from './appearance.js';

/** Channel row (shared with the player's channel list). Honors Settings → Appearance → Live TV display options. */
export function channelRowContent(c, { index, extra } = {}) {
  const u = ui();
  const pct = c.now ? Math.min(100, ((nowS() - c.now.start) / (c.now.stop - c.now.start)) * 100) : 0;
  return [
    u.chShowNumbers !== false ? h('div', { class: 'num' }, index !== undefined ? index + 1 : (c.num ?? '')) : null,
    u.chShowLogos !== false ? logo(c.logo, c.name) : null,
    h('div', { class: 'grow' },
      h('div', { class: 'nm ellipsis' }, c.name, c.archive ? h('span', { class: 'badge', style: { marginLeft: '6px' }, title: 'Catch-up available' }, '⟲') : null),
      h('div', { class: 'pr ellipsis' }, c.now ? `${fmtTime(c.now.start)}  ${c.now.title}` : ''),
      c.now && u.chShowProgress !== false ? h('div', { class: 'progress' }, h('i', { style: { width: `${pct}%` } })) : null,
      c.next && u.chShowNext ? h('div', { class: 'pr ellipsis dim' }, `Next ${fmtTime(c.next.start)}  ${c.next.title}`) : null),
    extra || null,
  ];
}

