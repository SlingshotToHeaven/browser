/**
 * FingerprintDefense.js - Injected at document_start
 * Neutralizes Canvas, WebGL, AudioContext, and Hardware Fingerprinting.
 */
(function() {
    'use strict';

    function notifyShield(apiType) {
        try {
            if (window.PrivacyShieldBridge && typeof window.PrivacyShieldBridge.postMessage === 'function') {
                window.PrivacyShieldBridge.postMessage(JSON.stringify({
                    type: 'FINGERPRINT_INTERCEPTED',
                    api: apiType,
                    timestamp: Date.now()
                }));
            }
        } catch (e) {
            // Silently absorb bridge reporting errors
        }
    }

    // =========================================================================
    // 1. Canvas Fingerprint Protection (Micro-Noise Injection)
    // =========================================================================
    try {
        const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
        HTMLCanvasElement.prototype.toDataURL = function() {
            try {
                if (this.width > 0 && this.height > 0) {
                    const ctx = this.getContext('2d');
                    if (ctx) {
                        const imgData = ctx.getImageData(0, 0, 1, 1);
                        // Perturb the red channel by 1 least-significant bit
                        imgData.data[0] = (imgData.data[0] ^ 1);
                        ctx.putImageData(imgData, 0, 0);
                        notifyShield('canvas_todataurl');
                    }
                }
            } catch (e) {}
            return origToDataURL.apply(this, arguments);
        };

        const origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
        CanvasRenderingContext2D.prototype.getImageData = function() {
            const imgData = origGetImageData.apply(this, arguments);
            try {
                if (imgData.data && imgData.data.length >= 4) {
                    // Inject undetectable micro-noise at first pixel
                    imgData.data[0] = (imgData.data[0] ^ 1);
                    notifyShield('canvas_getimagedata');
                }
            } catch (e) {}
            return imgData;
        };
    } catch (e) {}

    // =========================================================================
    // 2. WebGL GPU Vendor & Renderer Masking
    // =========================================================================
    try {
        const UNMASKED_VENDOR_WEBGL = 37445;
        const UNMASKED_RENDERER_WEBGL = 37446;

        function hookWebGL(proto) {
            if (!proto || !proto.getParameter) return;
            const origGetParam = proto.getParameter;
            proto.getParameter = function(param) {
                if (param === UNMASKED_VENDOR_WEBGL) {
                    notifyShield('webgl_vendor');
                    return 'ARM';
                }
                if (param === UNMASKED_RENDERER_WEBGL) {
                    notifyShield('webgl_renderer');
                    return 'Mali-G78 MP14';
                }
                return origGetParam.apply(this, arguments);
            };
        }

        if (typeof WebGLRenderingContext !== 'undefined') {
            hookWebGL(WebGLRenderingContext.prototype);
        }
        if (typeof WebGL2RenderingContext !== 'undefined') {
            hookWebGL(WebGL2RenderingContext.prototype);
        }
    } catch (e) {}

    // =========================================================================
    // 3. AudioContext Fingerprint Perturbation
    // =========================================================================
    try {
        if (typeof AnalyserNode !== 'undefined') {
            const origGetFloatFreq = AnalyserNode.prototype.getFloatFrequencyData;
            AnalyserNode.prototype.getFloatFrequencyData = function(array) {
                origGetFloatFreq.apply(this, arguments);
                if (array && array.length > 0) {
                    for (let i = 0; i < Math.min(array.length, 10); i++) {
                        array[i] += (Math.sin(i) * 0.0001);
                    }
                    notifyShield('audio_frequency');
                }
            };

            const origGetByteFreq = AnalyserNode.prototype.getByteFrequencyData;
            AnalyserNode.prototype.getByteFrequencyData = function(array) {
                origGetByteFreq.apply(this, arguments);
                if (array && array.length > 0) {
                    array[0] = (array[0] ^ 1);
                    notifyShield('audio_byte_frequency');
                }
            };
        }
    } catch (e) {}

    // =========================================================================
    // 4. Hardware & Navigator Normalization
    // =========================================================================
    try {
        Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', {
            get: () => 4,
            configurable: true
        });
        Object.defineProperty(Navigator.prototype, 'deviceMemory', {
            get: () => 8,
            configurable: true
        });
        Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
            get: () => 5,
            configurable: true
        });

        // Suppress battery status API
        if ('getBattery' in Navigator.prototype) {
            Navigator.prototype.getBattery = () => Promise.reject(new Error('Battery API disabled'));
        }
    } catch (e) {}

    // =========================================================================
    // 5. Sensor & DeviceOrientation Suppression
    // =========================================================================
    try {
        window.DeviceOrientationEvent = undefined;
        window.DeviceMotionEvent = undefined;
    } catch (e) {}

})();
