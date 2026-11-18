import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { setImmediate } from 'node:timers/promises';
import vm from 'node:vm';
import test from 'node:test';

const script = readFileSync(new URL('../downloader/src/main/assets/downloader/blob_intake.js', import.meta.url), 'utf8');
const origin = 'https://example.test';
const url = `blob:${origin}/a-fixture`;

function page({ chunks = [Uint8Array.of(1, 2, 3)], length = '3', mainFrame = true } = {}) {
    const frames = [];
    const events = {};
    const bridge = { postMessage: message => frames.push(message) };
    const window = { PrivacyBrowserBlobIntake: bridge, addEventListener: (type, fn) => { events[type] = fn; } };
    window.top = mainFrame ? window : {};
    let reads = 0;
    let fetches = 0;
    let options;
    let releases = 0;
    const fetch = async (target, init) => {
        assert.equal(target, url);
        options = init;
        fetches++;
        return {
            status: 200,
            headers: { get: name => name === 'Content-Length' ? length : null },
            body: { getReader: () => ({
                read: async () => {
                    const value = chunks[reads++];
                    return { done: value == null, value };
                },
                releaseLock: () => { releases++; },
            }) },
            blob: () => assert.fail('No whole-blob reads'),
        };
    };
    vm.runInNewContext(`(${script})('page-1')`, {
        window, location: { protocol: 'https:', origin }, fetch, URL, AbortController, btoa,
    });
    return {
        frames,
        events,
        send: (type, token = 'attempt-1', extras) => bridge.onmessage?.({
            data: JSON.stringify({ type, token, ...extras }),
        }),
        start(extras) { this.send('start', 'attempt-1', { url, chunkBytes: 65536, maxBytes: 536870912, ...extras }); },
        get reads() { return reads; },
        get fetches() { return fetches; },
        get options() { return options; },
        get releases() { return releases; },
    };
}

test('binding, dismissing and a child frame do not fetch or read bytes', async () => {
    const tab = page();
    assert.deepEqual(tab.frames, ['ready:page-1']);
    tab.send('ack');
    tab.send('stop'); // no one ever confirmed this offer
    await setImmediate();
    assert.equal(tab.fetches, 0);
    assert.equal(tab.reads, 0);
    tab.events.pagehide();
    tab.start(); // a queued native message must not restart an exited document
    await setImmediate();
    assert.deepEqual(tab.frames, ['ready:page-1', 'gone:page-1']);
    assert.equal(tab.fetches, 0);
    const child = page({ mainFrame: false });
    child.start();
    assert.deepEqual(child.frames, []);
    assert.equal(child.fetches, 0);
});

test('a confirmed receiver is paced by acks, with bounded slices and explicit end', async () => {
    const payload = new Uint8Array(65539).fill(172);
    const tab = page({ chunks: [payload], length: String(payload.length) });
    tab.start();
    await setImmediate();
    assert.equal(tab.fetches, 1);
    assert.equal(tab.options.credentials, 'omit');
    assert.equal(tab.reads, 0); // wait until the native worker checks the declared length
    assert.deepEqual(tab.frames, ['ready:page-1', 'attempt-1:begin:65539']);
    tab.send('ack', 'other-attempt');
    await setImmediate();
    assert.equal(tab.reads, 0);
    tab.send('ack');
    await setImmediate();
    assert.equal(tab.reads, 1);
    assert.equal(tab.frames.length, 3);
    assert.equal(tab.frames[2], `attempt-1:data:0:${Buffer.from(payload.subarray(0, 65536)).toString('base64')}`);
    tab.send('ack');
    await setImmediate();
    assert.equal(tab.reads, 1);
    assert.equal(tab.frames[3], 'attempt-1:data:65536:rKys');
    tab.send('ack');
    await setImmediate();
    assert.equal(tab.reads, 2);
    assert.equal(tab.frames[4], 'attempt-1:end:65539');
    assert.equal(tab.releases, 1);
});

test('stop and pagehide abort reads, release the reader and ignore late acks', async () => {
    for (const closeTab of [false, true]) {
        const tab = page();
        tab.start();
        await setImmediate();
        tab.send('ack');
        await setImmediate();
        assert.equal(tab.reads, 1);
        const before = tab.frames.length;
        if (closeTab) tab.events.pagehide();
        else tab.send('stop');
        tab.send('ack');
        await setImmediate();
        assert.equal(tab.options.signal.aborted, true);
        assert.equal(tab.releases, 1);
        assert.equal(tab.reads, 1);
        assert.deepEqual(tab.frames.slice(before), closeTab ? ['gone:page-1'] : []);
    }
});

test('bad origins, oversized streams and short declared streams never send an end', async () => {
    const crossOrigin = page();
    crossOrigin.start({ url: 'blob:https://not-example.test/fixture' });
    await setImmediate();
    assert.equal(crossOrigin.fetches, 0);
    assert.equal(crossOrigin.frames.at(-1), 'attempt-1:error');
    for (const { length, maxBytes } of [{ length: '4', maxBytes: 4 }, { length: null, maxBytes: 2 }]) {
        const tab = page({ length });
        tab.start({ maxBytes });
        await setImmediate();
        tab.send('ack');
        await setImmediate();
        tab.send('ack');
        await setImmediate();
        assert.equal(tab.frames.at(-1), 'attempt-1:error');
        assert.ok(tab.frames.every(frame => !frame.startsWith('attempt-1:end:')));
        assert.equal(tab.releases, 1);
    }
});
