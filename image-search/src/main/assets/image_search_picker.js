(function() {
    if (window.__imageSearchPickerActive) {
        if (window.__cleanupImageSearchPicker) {
            window.__cleanupImageSearchPicker(false);
        }
    }
    window.__imageSearchPickerActive = true;

    // Helper CSS: disable video/audio pointer interception and add image tap pulse effect
    var styleEl = document.createElement('style');
    styleEl.id = '__image_search_picker_style';
    styleEl.textContent = 'video, audio { pointer-events: none !important; }';
    (document.head || document.documentElement).appendChild(styleEl);

    // Create banner
    var banner = document.createElement('div');
    banner.id = '__image_search_picker_banner';
    banner.style.position = 'fixed';
    banner.style.top = '14px';
    banner.style.left = '16px';
    banner.style.right = '16px';
    banner.style.zIndex = '2147483647';
    banner.style.backgroundColor = '#1E1E2E';
    banner.style.color = '#FFFFFF';
    banner.style.borderRadius = '12px';
    banner.style.boxShadow = '0 8px 24px rgba(0,0,0,0.6)';
    banner.style.padding = '12px 16px';
    banner.style.display = 'flex';
    banner.style.alignItems = 'center';
    banner.style.justifyContent = 'space-between';
    banner.style.fontFamily = 'system-ui, -apple-system, Roboto, sans-serif';
    banner.style.fontSize = '14px';
    banner.style.border = '1px solid #313244';
    banner.style.pointerEvents = 'auto';

    var text = document.createElement('span');
    text.innerText = 'Tap any image on the page to search';
    text.style.fontWeight = '500';
    banner.appendChild(text);

    var cancelBtn = document.createElement('button');
    cancelBtn.innerText = 'Cancel';
    cancelBtn.style.background = '#45475A';
    cancelBtn.style.color = '#CDD6F4';
    cancelBtn.style.border = 'none';
    cancelBtn.style.borderRadius = '6px';
    cancelBtn.style.padding = '6px 14px';
    cancelBtn.style.fontSize = '12px';
    cancelBtn.style.fontWeight = 'bold';
    cancelBtn.style.cursor = 'pointer';
    cancelBtn.onclick = function(e) {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        cleanup(true);
    };
    banner.appendChild(cancelBtn);
    (document.body || document.documentElement).appendChild(banner);

    function cleanup(isCancelled) {
        window.__imageSearchPickerActive = false;
        if (banner && banner.parentNode) banner.parentNode.removeChild(banner);
        var s = document.getElementById('__image_search_picker_style');
        if (s && s.parentNode) s.parentNode.removeChild(s);

        window.removeEventListener('click', onClick, true);
        window.removeEventListener('touchend', onTouchEnd, true);

        if (isCancelled && window.ImageSearchBridge && window.ImageSearchBridge.onPickerCancelled) {
            window.ImageSearchBridge.onPickerCancelled();
        }
    }
    window.__cleanupImageSearchPicker = cleanup;

    function toAbsolute(url) {
        if (!url) return null;
        if (url.startsWith('data:')) return url;
        try {
            return new URL(url, document.baseURI || window.location.href).href;
        } catch(e) {
            return url;
        }
    }

    function extractImageFromNode(node) {
        if (!node || node === banner || banner.contains(node)) return null;
        var tag = (node.nodeName || '').toLowerCase();

        // 1. <img> element
        if (tag === 'img') {
            var src = node.currentSrc || node.src || node.getAttribute('src') ||
                      node.getAttribute('data-src') || node.getAttribute('data-original') ||
                      node.getAttribute('data-lazy-src') || node.getAttribute('data-url') ||
                      node.getAttribute('data-hi-res-src');
            if (src && !src.startsWith('data:image/svg+xml')) return toAbsolute(src);
        }

        // 2. <picture>
        if (tag === 'picture') {
            var imgChild = node.querySelector('img');
            if (imgChild) return extractImageFromNode(imgChild);
            var source = node.querySelector('source');
            if (source && source.srcset) {
                var firstUrl = source.srcset.split(',')[0].trim().split(' ')[0];
                if (firstUrl) return toAbsolute(firstUrl);
            }
        }

        // 3. SVG <image>
        if (tag === 'image') {
            var href = node.getAttribute('href') || node.getAttribute('xlink:href');
            if (href) return toAbsolute(href);
        }

        // 4. <canvas>
        if (tag === 'canvas') {
            try {
                var d = node.toDataURL('image/png');
                if (d && d.length > 100) return d;
            } catch (e) {}
        }

        // 5. <video> poster
        if (tag === 'video' && node.poster) {
            return toAbsolute(node.poster);
        }

        // 6. Check CSS computed background-image
        var style = window.getComputedStyle(node);
        var bg = style ? style.getPropertyValue('background-image') : '';
        if (bg && bg !== 'none') {
            var match = /url\s*\(\s*['"]?(.*?)['"]?\s*\)/i.exec(bg);
            if (match && match[1]) {
                return toAbsolute(match[1]);
            }
        }

        // 7. Check direct children
        var nestedImg = node.querySelector('img, canvas');
        if (nestedImg) {
            return extractImageFromNode(nestedImg);
        }

        // 8. Walk up parent if shallow (up to 3 levels)
        var p = node.parentElement;
        var depth = 0;
        while (p && depth < 3 && p !== document.body && p !== document.documentElement) {
            var pStyle = window.getComputedStyle(p);
            var pBg = pStyle ? pStyle.getPropertyValue('background-image') : '';
            if (pBg && pBg !== 'none') {
                var pMatch = /url\s*\(\s*['"]?(.*?)['"]?\s*\)/i.exec(pBg);
                if (pMatch && pMatch[1]) return toAbsolute(pMatch[1]);
            }
            depth++;
            p = p.parentElement;
        }

        return null;
    }

    function findImageUrl(target, clientX, clientY) {
        // 1. Direct target check
        var directUrl = extractImageFromNode(target);
        if (directUrl) return directUrl;

        // 2. Piercing point check through all stacked elements at touch point
        if (typeof clientX === 'number' && typeof clientY === 'number') {
            var elements = document.elementsFromPoint ? document.elementsFromPoint(clientX, clientY) : [document.elementFromPoint(clientX, clientY)];
            if (elements) {
                for (var i = 0; i < elements.length; i++) {
                    var el = elements[i];
                    if (!el || el === banner || banner.contains(el)) continue;
                    var u = extractImageFromNode(el);
                    if (u) return u;
                }
            }

            // 3. Search in a 24px radius around touch center
            var deltas = [[0, -24], [0, 24], [-24, 0], [24, 0], [-16, -16], [16, 16], [-16, 16], [16, -16]];
            for (var d = 0; d < deltas.length; d++) {
                var nx = clientX + deltas[d][0];
                var ny = clientY + deltas[d][1];
                var nearElements = document.elementsFromPoint ? document.elementsFromPoint(nx, ny) : [];
                if (nearElements) {
                    for (var k = 0; k < nearElements.length; k++) {
                        var nEl = nearElements[k];
                        if (!nEl || nEl === banner || banner.contains(nEl)) continue;
                        var nu = extractImageFromNode(nEl);
                        if (nu) return nu;
                    }
                }
            }
        }
        return null;
    }

    function handleEvent(e) {
        if (banner && banner.contains(e.target)) return;

        var x = e.clientX || (e.changedTouches && e.changedTouches[0] ? e.changedTouches[0].clientX : 0);
        var y = e.clientY || (e.changedTouches && e.changedTouches[0] ? e.changedTouches[0].clientY : 0);

        var imgUrl = findImageUrl(e.target, x, y);
        if (imgUrl) {
            e.preventDefault();
            e.stopPropagation();
            e.stopImmediatePropagation();
            cleanup(false);
            if (window.ImageSearchBridge && window.ImageSearchBridge.onImageSelected) {
                window.ImageSearchBridge.onImageSelected(imgUrl);
            }
        }
    }

    function onClick(e) {
        handleEvent(e);
    }

    function onTouchEnd(e) {
        handleEvent(e);
    }

    window.addEventListener('click', onClick, true);
    window.addEventListener('touchend', onTouchEnd, true);
})();
