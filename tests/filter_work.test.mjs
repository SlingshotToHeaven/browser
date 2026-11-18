import { readFileSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { runInContext, createContext } from 'node:vm';

const load = name => readFileSync(new URL(`../app/src/main/assets/filters/scriptlets/${name}.js`, import.meta.url), 'utf8');
const domWork = load('bounded_dom_work');

// Tests JS work and ordering, not Chromium's CSS engine or layout. The device corpus covers that.
function fixture({ strict = false, bridge } = {}) {
  const frames = [], observers = [], events = {}, operations = [];
  let walks = 0, visited = 0, largestSlice = 0;
  const document = {};
  function node(tag, { id = '', className = '', style = '', src = '', resolved = src, ad = false,
    role = '', content = false, computed, size = 80 } = {}) {
    const el = {
      nodeType: 1, tagName: tag.toUpperCase(), id, src: resolved, children: [], ownerDocument: document,
      connected: true, computed, writes: [], sheet: strict ? null : {},
      get isConnected() { return this.connected && (!this.parent || this.parent.isConnected); },
      appendChild(child) { this.children.push(child); child.parent = this; return child; },
      style: { setProperty(property, value, priority) { el.writes.push([property, value, priority]); } },
      getAttribute(attribute) { return { class: className, style, src, role }[attribute] ?? null; },
      hasAttributes() { return Boolean(id || className || style || src || ad || role); },
      matches(selectors) {
        if (selectors.includes('mol-para-with-font')) return content;
        if (selectors.startsWith('iframe[')) return /doubleclick|smartadserver/i.test(src);
        return ad && selectors.includes('.adsbygoogle');
      },
      getBoundingClientRect() { operations.push(`rect:${id}`); return { width: size, height: size }; },
      remove() { operations.push(`remove:${id}`); this.connected = false; },
    };
    return el;
  }
  const html = node('html'), head = html.appendChild(node('head')), body = html.appendChild(node('body'));
  Object.assign(document, {
    documentElement: html, head, body, readyState: 'complete',
    createElement: node,
    getElementById: id => head.children.find(el => el.id === id),
    querySelectorAll() { throw new Error('no document-wide queries in stock scriptlets'); },
    createTreeWalker(root) {
      const stack = [{ el: root, i: 0 }];
      return { nextNode() {
        walks++;
        while (stack.length) {
          const top = stack.at(-1), child = top.el.children[top.i++];
          if (!child) { stack.pop(); continue; }
          stack.push({ el: child, i: 0 });
          return child;
        }
        return null;
      } };
    },
    addEventListener(name, cb) { (events[name] ??= []).push(cb); },
  });
  const nativeOpen = () => 'native open';
  const window = {
    location: { href: 'https://fixture.invalid/' }, open: nativeOpen, innerWidth: 100, innerHeight: 100,
    PrivacyAntiAdblockBridge: bridge,
    requestAnimationFrame(fn) { frames.push(fn); },
    setTimeout() { throw new Error('no timers when rAF is available'); },
    MutationObserver: class { constructor(fn) { observers.push(fn); } observe() {} },
    getComputedStyle(el) {
      operations.push(`style:${el.id}`);
      return el.computed ?? { position: 'static', display: 'none' };
    },
  };
  const context = createContext({ window, document, setTimeout: window.setTimeout });
  return {
    node, html, head, body, window, nativeOpen, operations, frames, events, observers,
    get walks() { return walks; }, get visited() { return visited; }, get largestSlice() { return largestSlice; },
    run(name) { runInContext(`(function(__privacyObserveElements){\n${load(name)}\n})(\n${domWork}\n);`, context); },
    observe(root = html) {
      context.root = root;
      context.visit = nodes => { visited += nodes.size; largestSlice = Math.max(largestSlice, nodes.size); };
      runInContext(`(${domWork})(root,['style'],()=>true,visit)`, context);
    },
    emit(...records) { observers.forEach(fn => fn(records)); },
    flush() {
      let slices = 0;
      while (frames.length) {
        assert.ok(++slices < 400, 'finite work once mutations stop');
        frames.shift()();
      }
    },
  };
}
const added = (...nodes) => ({ type: 'childList', addedNodes: nodes });
const attr = node => ({ type: 'attributes', target: node });

// The fake matcher does not read computed CSS, so ordinary and new ads must not require inline writes.
test('cosmetic CSS covers ordinary and inserted ads without repeated pruning', () => {
  const f = fixture();
  const ad = f.body.appendChild(f.node('div', { ad: true }));
  f.run('cosmetic_pruner');
  f.run('cosmetic_pruner');
  assert.equal(f.observers.length, 1);
  assert.match(f.head.children[0].textContent, /\.adsbygoogle/);
  assert.match(f.head.children[0].textContent, /object\[src\*="smartadserver" i\]/);
  f.flush();
  const later = f.body.appendChild(f.node('iframe', { src: 'https://SMARTADSERVER.invalid/' }));
  f.emit(added(later));
  f.flush();
  assert.deepEqual(ad.writes, []);
  assert.deepEqual(later.writes, []);
  assert.deepEqual(f.operations, ['style:', 'style:']); // CSS wins without inline writes or geometry
});

test('bounded inline recovery covers conflicts, resolved frame URLs and blocked stylesheets', () => {
  const f = fixture();
  const override = f.body.appendChild(f.node('div', { ad: true, style: 'display:block !important' }));
  const cascade = f.body.appendChild(f.node('div', { ad: true, computed: { display: 'block' } }));
  const frame = f.body.appendChild(f.node('iframe', { src: './payload', resolved: 'https://doubleclick.invalid/payload' }));
  f.run('cosmetic_pruner');
  f.flush();
  assert.deepEqual(override.writes[0], ['display', 'none', 'important']);
  assert.deepEqual(cascade.writes[0], ['display', 'none', 'important']);
  assert.deepEqual(frame.writes[1], ['width', '0px', 'important']);
  const writes = override.writes.length;
  f.emit(attr(override), added(override));
  f.flush();
  assert.equal(override.writes.length, writes);

  const strict = fixture({ strict: true });
  const plain = strict.body.appendChild(strict.node('p', { ad: true }));
  const articleBox = strict.body.appendChild(strict.node('div', { ad: true }));
  articleBox.appendChild(strict.node('article', { content: true }));
  strict.run('cosmetic_pruner');
  strict.flush();
  assert.deepEqual(plain.writes[0], ['display', 'none', 'important']);
  assert.deepEqual(articleBox.writes, []);
});

test('initial work, queued added roots and a large added subtree each have explicit limits', () => {
  const f = fixture();
  for (let i = 0; i < 7000; i++) f.body.appendChild(f.node('p'));
  f.observe();
  f.flush();
  assert.equal(f.visited, 4096);
  assert.ok(f.largestSlice <= 64);
  const branch = f.body.appendChild(f.node('div'));
  for (let i = 0; i < 7000; i++) branch.appendChild(f.node('p'));
  let before = f.walks;
  f.emit(added(branch), added(branch));
  assert.equal(f.frames.length, 1);
  f.flush();
  assert.equal(f.visited, 4096 + 512);
  assert.ok(f.walks - before <= 512);
  const roots = Array.from({ length: 100 }, () => f.body.appendChild(f.node('p')));
  f.emit(added(...roots));
  f.flush();
  assert.equal(f.visited, 4096 + 512 + 32);
});

const curtain = { position: 'fixed', zIndex: '1000', opacity: '0.01', backgroundColor: 'red' };
test('popup reads once per candidate per slice, measures only eligible overlays, and then writes', () => {
  const f = fixture();
  const large = f.body.appendChild(f.node('div', { id: 'overlay', computed: curtain }));
  const staticDiv = f.body.appendChild(f.node('div', { id: 'content' }));
  const dialog = f.body.appendChild(f.node('div', { id: 'login', role: 'dialog', computed: curtain }));
  const small = f.body.appendChild(f.node('span', { id: 'small', computed: curtain, size: 18 }));
  f.run('popup_defuser');
  f.flush();
  assert.deepEqual(f.operations, ['style:overlay', 'rect:overlay', 'style:content', 'style:small', 'rect:small', 'remove:overlay']);
  assert.equal(large.isConnected, false);
  for (const el of [staticDiv, dialog, small]) assert.ok(el.isConnected);
  const later = f.body.appendChild(f.node('a', { id: 'late', computed: curtain }));
  f.operations.length = 0;
  f.emit(added(later), attr(later));
  f.flush();
  assert.deepEqual(f.operations, ['style:late', 'rect:late', 'remove:late']);
  assert.equal(f.frames.length, 0); // no idle style/geometry pass
});

test('popup URL and captured link decisions, install guard, and disabled site behavior are retained', () => {
  const f = fixture();
  f.run('popup_defuser');
  const installed = f.window.open;
  f.run('popup_defuser');
  assert.equal(f.window.open, installed);
  assert.equal(f.window.open('https://ordinary.invalid/'), 'native open');
  assert.equal(f.window.open('https://ads.invalid/pagead/fixture').closed, true);
  const changes = [];
  f.events.click[0]({ target: { closest: () => ({ href: 'https://popads.invalid/' }) },
    preventDefault: () => changes.push('default'), stopPropagation: () => changes.push('bubble'),
    stopImmediatePropagation: () => changes.push('immediate') });
  assert.deepEqual(changes, ['default', 'bubble', 'immediate']);

  const disabled = fixture({ bridge: { isAdBlockActive: () => false, isCosmeticEnabled: () => false } });
  disabled.run('popup_defuser');
  disabled.run('cosmetic_pruner');
  assert.equal(disabled.window.open, disabled.nativeOpen);
  assert.deepEqual(disabled.head.children, []);
  assert.equal(disabled.observers.length, 0);
});
