// Evaluated once at a main-frame commit. Binding the reply channel reads no blob data.
(function (documentChallenge) {
    if (window !== window.top || !/^https?:$/.test(location.protocol)) return;
    const bridge = window.PrivacyBrowserBlobIntake;
    if (!bridge) return;
    let active = null;

    function stop(session) {
        if (active !== session) return;
        active = null;
        session.controller.abort();
        if (session.waiting) session.waiting.reject(new Error('Blob download stopped'));
    }

    function post(session, frame) {
        bridge.postMessage(session.token + ':' + frame);
    }

    function sendAndWait(session, frame) {
        return new Promise((resolve, reject) => {
            if (active !== session) {
                reject(new Error('Blob download stopped'));
                return;
            }
            session.waiting = {resolve, reject};
            post(session, frame);
        });
    }

    async function readBlob(session, command) {
        let reader;
        try {
            const url = new URL(command.url);
            if (url.protocol !== 'blob:' || url.origin !== location.origin) throw new Error('Wrong blob origin');
            const response = await fetch(url.href, {signal: session.controller.signal, credentials: 'omit', redirect: 'error'});
            if (response.status !== 200) throw new Error('Blob unavailable');
            const header = response.headers.get('Content-Length');
            const declared = header === null ? -1 : /^\d+$/.test(header) ? Number(header) : NaN;
            if (!Number.isSafeInteger(declared) || declared > command.maxBytes) throw new Error('Invalid blob length');
            if (!response.body && declared !== 0) throw new Error('Streaming blob reads are not supported');

            await sendAndWait(session, 'begin:' + declared);
            let offset = 0;
            if (response.body) {
                reader = response.body.getReader();
                for (;;) {
                    const {value, done} = await reader.read();
                    if (active !== session) return;
                    if (done) break;
                    if (value.byteLength > command.maxBytes - offset || (declared >= 0 && value.byteLength > declared - offset)) {
                        throw new Error('Invalid blob length');
                    }
                    for (let i = 0; i < value.byteLength; i += command.chunkBytes) {
                        const end = Math.min(value.byteLength, i + command.chunkBytes);
                        let binary = '';
                        for (let j = i; j < end; j += 8192) {
                            binary += String.fromCharCode.apply(null, value.subarray(j, Math.min(j + 8192, end)));
                        }
                        await sendAndWait(session, 'data:' + offset + ':' + btoa(binary));
                        offset += end - i;
                    }
                }
            }
            if (active !== session) return;
            if (declared >= 0 && offset !== declared) throw new Error('Short blob');
            post(session, 'end:' + offset);
        } catch (_) {
            if (active === session) post(session, 'error');
        } finally {
            stop(session);
            if (reader) reader.releaseLock();
        }
    }

    const onCommand = event => {
        let command;
        try { command = JSON.parse(event.data); } catch (_) { return; }
        if (!command || typeof command.token !== 'string') return;
        if (command.type === 'start' && !active && Number.isSafeInteger(command.chunkBytes)
                && command.chunkBytes > 0 && command.chunkBytes <= 65536 && Number.isSafeInteger(command.maxBytes)) {
            active = {token: command.token, controller: new AbortController(), waiting: null};
            readBlob(active, command);
        } else if (active && active.token === command.token) {
            if (command.type === 'ack' && active.waiting) {
                const waiting = active.waiting;
                active.waiting = null;
                waiting.resolve();
            } else if (command.type === 'stop') {
                stop(active);
            }
        }
    };
    bridge.onmessage = onCommand;
    window.addEventListener('pagehide', () => {
        if (bridge.onmessage === onCommand) bridge.onmessage = null;
        if (active) stop(active);
        bridge.postMessage('gone:' + documentChallenge);
    }, {once: true});
    bridge.postMessage('ready:' + documentChallenge);
})
