import test from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { javaTextBlock } from './fixtures/java_text_block.mjs';

// Run the production injected script, not a second implementation of the detector.
const script = javaTextBlock('../app/src/main/java/com/privacy/browser/comic/ComicDetector.java', 'DETECTOR_JS');
const origin = 'https://reader.invalid';

function image(url) {
  return { tagName: 'IMG', currentSrc: url, naturalWidth: 500, naturalHeight: 800, getAttribute: () => null };
}

function harness({ plans = {}, path = '/chapter', navCount = 0, selects = [], strip = null, gallery = [] } = {}) {
  const reports = [], timers = new Map(), observers = [], listeners = [];
  const work = { signals: 0, strip: 0, gallery: 0 };
  const window = {
    location: { href: origin + path }, ...plans,
    __privacyPageReport: (kind, urls, proof) => { reports.push({ kind, urls: Array.from(urls), proof }); },
    addEventListener: kind => { listeners.push(kind); },
  };
  const reader = strip && {
    tagName: 'DIV',
    isConnected: true,
    getElementsByTagName: tag => { assert.equal(tag, 'img'); work.strip++; return strip; },
  };
  const document = {
    get baseURI() { return window.location.href; },
    get images() { work.gallery++; return gallery; },
    getElementsByTagName: tag => { assert.equal(tag, 'select'); return selects; },
    querySelector: selector => {
      work.signals++;
      if (selector.startsWith('.reading-content')) return reader;
      if (selector.startsWith('.pagination') && navCount) {
        return { textContent: 'Page 1 of ' + navCount, getAttribute: () => null };
      }
      return null;
    },
  };
  class MutationObserver {
    constructor(callback) { this.callback = callback; this.stopped = false; observers.push(this); }
    observe(root, options) { this.root = root; this.options = options; }
    disconnect() { this.stopped = true; }
  }
  let sequence = 0;
  runInNewContext(script, { window, document, URL, MutationObserver,
    setTimeout: task => { timers.set(++sequence, task); return sequence; },
    clearTimeout: id => timers.delete(id),
  }, { timeout: 1000 });
  return {
    detector: window.__privacyComicDetector, window, document, reader, reports, timers, observers, listeners, work,
    start() { this.detector.start('current', false); },
    change(records) { observers.at(-1).callback(records); },
    runTimers() { const pending = [...timers.values()]; timers.clear(); pending.forEach(run => run()); },
  };
}

function detect(config, manual = false) {
  const p = harness(config);
  p.start();
  if (manual) p.detector.manual('current', 'gesture');
  const report = p.reports.at(-1);
  assert.equal(report.kind, manual ? 'comic-manual' : 'comic');
  assert.equal(report.proof, manual ? 'gesture' : 'current');
  return report.urls;
}

test('declared positions include repeated page URLs and preserve path and query', () => {
  const href = '/Thumbs/001.png?w=700&sig=A%2f';
  assert.deepEqual(detect({ plans: { chapterImages: [href, { image: '/two.png' }, href] } }),
    [origin + href, origin + '/two.png', origin + href]);
  assert.deepEqual(detect({ strip: [image(href), image('/two.png'), image(href)] }),
    [origin + href, origin + '/two.png', origin + href]);
});

test('an invalid slot in a known list or strip cannot become a smaller successful plan, even when asked', () => {
  for (const bad of [null, '', 'data:image/png,not-a-page', 'https://name:secret@reader.invalid/a.png']) {
    const gallery = [image('/substitute.png'), image('/spare.png')];
    assert.deepEqual(detect({ plans: { chapterImages: ['/one.png', bad, '/three.png'] }, gallery }, true), []);
    assert.deepEqual(detect({ strip: [image('/one.png'), image(bad), image('/three.png')], gallery }, true), []);
  }
});

test('an oversized detected chapter is refused, not truncated', () => {
  const array = Array.from({ length: 150 }, (_, i) => `/page-${i + 1}.png`);
  assert.equal(detect({ plans: { chapterImages: array } }).at(-1), origin + '/page-150.png');
  assert.deepEqual(detect({ plans: { chapterImages: [...array, '/page-151.png'] } }), []);
  assert.deepEqual(detect({ strip: [...array, '/page-151.png'].map(image) }), []);
  assert.deepEqual(detect({ path: '/chapter/page/1', navCount: 151 }), []);
  assert.deepEqual(detect({ gallery: [...array, '/page-151.png'].map(image) }, true), []);
  assert.deepEqual(detect({ gallery: Array.from({ length: 513 }, () => image('/page.png')) }, true), []);
});

test('a plan over the message budget clears the detection instead of leaving a stale plan', () => {
  const long = Array.from({ length: 20 }, (_, i) => `/page-${i}?fixture=${'x'.repeat(7900)}`);
  assert.deepEqual(detect({ plans: { chapterImages: long } }), []);
});

test('paginated readers preserve all slots, query suffix and trailing slash', () => {
  const selects = [{ name: 'page', options: [{ text: 'Page 1', value: '1' }, { text: 'Page 3', value: '3' }] }];
  assert.deepEqual(detect({ path: '/chapter/page/1/?fixture=1', selects }),
    [1, 2, 3].map(n => `${origin}/chapter/page/${n}/?fixture=1`));
  assert.deepEqual(detect({ path: '/chapter?mode=wide&p=2&sig=ABC#reader', selects }),
    [1, 2, 3].map(n => `${origin}/chapter?mode=wide&p=${n}&sig=ABC#reader`));
});

test('ordinary image-heavy pages do zero gallery work or observers until an explicit action', () => {
  const gallery = Array.from({ length: 300 }, (_, i) => image('/image-' + i + '.png'));
  const p = harness({ gallery, plans: { images: ['/not-a-reader', '/other'] } });
  p.start();
  p.detector.start('current', true); // native finish check
  for (let i = 0; i < 100; i++) p.detector.start('current', true);
  assert.deepEqual(p.work, { signals: 4, strip: 0, gallery: 0 });
  assert.equal(p.reports.length, 0);
  assert.equal(p.observers.length, 0);
  assert.deepEqual(p.listeners, []); // no scroll, document-wide observing or interval
  assert.equal(p.timers.size, 0);

  const manual = harness({ gallery: [image('/A.png'), image('/B.png'), image('/A.png')] });
  manual.start();
  manual.detector.manual('current', 'click');
  assert.equal(manual.work.gallery, 1);
  assert.deepEqual(manual.reports[0], { kind: 'comic-manual', proof: 'click', urls: ['/A.png', '/B.png', '/A.png'].map(x => origin + x) });
});

test('no reader rescan at finish or on unchanged DOM writes; a changed source burst coalesces once', () => {
  const a = image('/a.png'), b = image('/b.png');
  const p = harness({ strip: [a, b] });
  p.start();
  p.detector.start('current', true);
  assert.equal(p.work.strip, 1);
  assert.equal(p.observers.length, 1);
  assert.equal(p.observers[0].root, p.reader);
  const attr = oldValue => ({ type: 'attributes', target: b, attributeName: 'src', oldValue });
  b.getAttribute = key => key === 'src' ? b.currentSrc : null;
  p.change(Array.from({ length: 75 }, () => attr('/b.png')));
  assert.equal(p.timers.size, 0);
  b.currentSrc = '/new.png';
  for (let i = 0; i < 65; i++) p.change([attr('/b.png')]);
  assert.equal(p.timers.size, 1);
  p.runTimers();
  assert.equal(p.work.strip, 2);
  assert.deepEqual(p.reports.at(-1).urls, [origin + '/a.png', origin + '/new.png']);
  // A changed placeholder attr whose chosen high-res source is the same does not republish.
  p.change([{...attr('/old-srcset.png'), attributeName: 'srcset'}]);
  p.runTimers();
  assert.equal(p.reports.length, 2);
});

test('late reader signals get one finish recheck and SPA or suspension retires old callbacks', () => {
  const late = harness();
  late.start();
  late.window.chapterImages = ['/a.png', '/b.png'];
  late.detector.start('current', true);
  assert.equal(late.reports.length, 1);
  const p = harness({ strip: [image('/a.png'), image('/b.png')] });
  p.start();
  p.change([{ type: 'childList', target: p.reader, addedNodes: [image('/c.png')], removedNodes: [] }]);
  assert.equal(p.timers.size, 1);
  p.window.location.href = origin + '/replacement';
  p.runTimers();
  assert.equal(p.reports.length, 1);
  assert.equal(p.observers[0].stopped, true);
  p.detector.start('replacement', true, true);
  assert.equal(p.reports.length, 1);
  p.window.chapterImages = ['/new1.png', '/new2.png'];
  p.runTimers();
  assert.deepEqual(p.reports.at(-1), { kind: 'comic', proof: 'replacement', urls: ['/new1.png', '/new2.png'].map(x => origin + x) });
  p.detector.manual('current', 'old');
  p.detector.stop('replacement');
  p.detector.manual('replacement', 'paused');
  assert.equal(p.reports.length, 2);
  assert.equal(p.observers[1].stopped, true);
});
