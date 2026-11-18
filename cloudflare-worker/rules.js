// Mirror FilterSnapshot's data-only subset. The Android client still revalidates every import.
const ruleLimits = { hosts: 450_000, network: 12_000, cosmetic: 12_000, user_filters: 12_000 };
const hostPattern = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$/;
const pathPattern = /^\/[A-Za-z0-9/._~%!&'()+,;=:@?\-]*$/;
const control = /[\u0000-\u001f\u007f-\u009f]/;
const trim = text => text.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '');
const host = text => text.toLowerCase().replace(/\.$/, '');
const validHost = value => value.length <= 253 && hostPattern.test(value);

function validSelector(selector) {
  if (!selector || /^\+js\(|^\^|\/\*|:has-text\(|:matches-|:xpath\(|:remove\(|:style\(|:-abp-|:upward\(|:watch-attr\(|:min-text-length\(/i.test(selector)) return false;
  let quote = '', piece = false;
  const stack = [];
  for (let i = 0; i < selector.length; i++) {
    const ch = selector[i];
    if (control.test(ch) || ch === '{' || ch === '}') return false;
    if (ch === '\\') {
      if (++i === selector.length || control.test(selector[i])) return false;
      piece = true;
      continue;
    }
    if (quote) {
      if (ch === quote) quote = '';
      continue;
    }
    if (ch === ';' || ch === '@') return false;
    if (ch === '"' || ch === "'") quote = ch;
    else if (ch === '(' || ch === '[') {
      if (stack.length === 32) return false;
      stack.push(ch);
    } else if (ch === ')' || ch === ']') {
      if (stack.pop() !== (ch === ')' ? '(' : '[')) return false;
    } else if (stack.length === 0 && ch === ',') {
      if (!piece) return false;
      piece = false;
      continue;
    }
    if (!/\s/u.test(ch)) piece = true;
  }
  return !quote && !stack.length && piece;
}

function validLine(key, line) {
  if (key === 'hosts') {
    const comment = line.indexOf('#');
    if (comment > 0 && /[ \t]/.test(line[comment - 1])) line = trim(line.slice(0, comment));
    line = host(trim(line.replace(/^(?:0\.0\.0\.0|127\.0\.0\.1)[ \t]/, '')));
    return line.includes('.') && validHost(line);
  }
  if (line.startsWith('||') || line.startsWith('@@||')) {
    if (key === 'cosmetic') return false;
    let target = line.slice(line.startsWith('@@') ? 4 : 2);
    const separator = target.endsWith('^');
    if (separator) target = target.slice(0, -1);
    const slash = target.indexOf('/');
    if (slash < 0) return separator && validHost(host(target));
    return validHost(host(target.slice(0, slash))) && pathPattern.test(target.slice(slash));
  }
  if (key === 'network') return false;
  const split = line.indexOf('##');
  if (split < 0 || !validSelector(trim(line.slice(split + 2)))) return false;
  const domains = trim(line.slice(0, split));
  return !domains || domains.split(',').every(domain => validHost(host(trim(domain))));
}

export function ruleError(key, text) {
  let rules = 0, number = 0;
  for (const raw of text.split(/\r\n|\r|\n/)) {
    number++;
    const line = trim(raw);
    if (!line || line.startsWith('!') || line === '#' || line.startsWith('# ') || line.startsWith('#\t')) continue;
    if (++rules > ruleLimits[key]) return `Too many rules in ${key}.`;
    if (line.length > 4096 || !validLine(key, line)) return `Unsupported rule at ${key} line ${number}.`;
  }
  return null;
}
