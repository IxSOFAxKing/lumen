// Parental lock (client side): PIN prompt + short-lived unlock token sent with API requests.
import { api } from './api.js';
import { h, modal, toast } from './ui.js';

const KEY = 'lumen.unlock';
export function unlockToken() {
  try {
    const t = JSON.parse(sessionStorage.getItem(KEY) || 'null');
    return t && t.expires > Date.now() ? t.token : null;
  } catch { return null; }
}
export const isUnlocked = () => !!unlockToken();
export function relock() { try { sessionStorage.removeItem(KEY); } catch { /* */ } api.clearCache(); }

/** Ask for the PIN. Resolves true when unlocked. */
export function promptPin(reason = 'This group is locked.') {
  return new Promise(resolve => {
    let ok = false;
    const input = h('input', { class: 'input', type: 'password', inputmode: 'numeric', maxlength: 8, placeholder: 'PIN', autofocus: true,
      style: { width: '100%', fontSize: '22px', letterSpacing: '8px', textAlign: 'center' } });
    const err = h('div', { class: 'small', style: { color: 'var(--err)', minHeight: '18px', marginTop: '6px' } });
    const submit = async () => {
      try {
        const t = await api.post('/api/iptv/parental/unlock', { pin: input.value });
        sessionStorage.setItem(KEY, JSON.stringify(t));
        api.clearCache();
        ok = true; m.close(); toast('Unlocked for 4 hours', { type: 'ok' });
      } catch (e) { err.textContent = e.message; input.value = ''; input.focus(); }
      return false;
    };
    input.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
    const m = modal({ title: '🔒 Enter PIN', body: h('div', {}, h('p', { class: 'muted', style: { marginTop: 0 } }, reason), input, err),
      actions: [{ label: 'Cancel' }, { label: 'Unlock', primary: true, onClick: submit }], onClose: () => resolve(ok) });
    setTimeout(() => input.focus(), 50);
  });
}
