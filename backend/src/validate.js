'use strict';

/**
 * Normalises a user-entered phone number to "+<digits>" or "<digits>".
 * Returns null when the input is not a plausible phone number.
 *
 * Accepts spaces, dashes, dots and parentheses as separators. Allows one
 * leading "+". Requires 7-15 digits (E.164 maximum is 15).
 */
function normalizePhone(raw) {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (trimmed.length === 0 || trimmed.length > 32) return null;
  if (/[^\d\s\-().+]/.test(trimmed)) return null; // letters etc.
  if (trimmed.indexOf('+', 1) !== -1) return null; // "+" only allowed first
  const digits = trimmed.replace(/\D/g, '');
  if (digits.length < 7 || digits.length > 15) return null;
  return (trimmed.startsWith('+') ? '+' : '') + digits;
}

/** Contact name is optional; trim, cap length, strip control chars. */
function normalizeName(raw) {
  if (raw === undefined || raw === null) return '';
  if (typeof raw !== 'string') return null;
  // eslint-disable-next-line no-control-regex
  const cleaned = raw.replace(/[\u0000-\u001f\u007f]/g, '').trim();
  if (cleaned.length > 100) return null;
  return cleaned;
}

module.exports = { normalizePhone, normalizeName };
