package sk.efabrica.resido.web

/**
 * JavaScript injected into pages loaded in the main WebView.
 */
object PageScripts {

    /**
     * Builds window.reservationClient with the Promise API the server-side
     * JS expects (printSilent(url).then(result => result.ok)), backed by the
     * synchronous JsBridge native object. Idempotent - it is installed via
     * addDocumentStartJavaScript and again from onPageFinished as a fallback
     * for WebView versions without document-start script support.
     */
    const val BRIDGE_SHIM_JS = """
        (() => {
            if (window.reservationClient) return;
            const pending = new Map();
            let seq = 0;
            window.__residoResolve = (id, result) => {
                const resolve = pending.get(id);
                if (resolve) {
                    pending.delete(id);
                    resolve(result);
                }
            };
            const call = (method, url) => new Promise((resolve) => {
                seq += 1;
                const id = 'p' + seq + '_' + Date.now();
                pending.set(id, resolve);
                // Safety net: never leave the page awaiting forever if the
                // native side dies mid-print.
                setTimeout(() => { window.__residoResolve(id, { ok: false }); }, 30000);
                try {
                    window.__residoNative[method](id, String(url));
                } catch (e) {
                    window.__residoResolve(id, { ok: false });
                }
            });
            window.reservationClient = {
                printSilent: (url) => call('printSilent', url),
                printSilentBon: (url) => call('printSilentBon', url),
                printSilentBonTwo: (url) => call('printSilentBonTwo', url),
                printSilentBonThree: (url) => call('printSilentBonThree', url),
                printSilentBonFour: (url) => call('printSilentBonFour', url),
                openSettings: () => {
                    try { window.__residoNative.openSettings(); } catch (e) {}
                }
            };
        })();
    """

    /**
     * Floating "Nastavenia" button on every internal page plus a "<" back
     * button on print-like pages - a verbatim port of the desktop client's
     * injectAppButtonsIfNeeded (resido.ps1), same element ids and styling so
     * users see the exact same UI.
     */
    const val BUTTONS_JS = """
        (() => {
            // doUpdateVisitedHistory can fire before the document has a body.
            if (!document.body) return;
            const isPrintLikePage = /\/(receipt|print(er)?)(\/|${'$'})/.test(window.location.pathname);
            const existingBackButton = document.getElementById('hotel-client-back-btn');
            let settingsButton = document.getElementById('hotel-client-settings-btn');

            if (!settingsButton) {
                settingsButton = document.createElement('button');
                settingsButton.id = 'hotel-client-settings-btn';
                settingsButton.type = 'button';
                settingsButton.textContent = 'Nastavenia';
                settingsButton.style.position = 'fixed';
                settingsButton.style.top = '14px';
                settingsButton.style.right = '134px';
                settingsButton.style.zIndex = '2147483647';
                settingsButton.style.padding = '10px 14px';
                settingsButton.style.border = '1px solid rgba(15, 23, 42, 0.18)';
                settingsButton.style.borderRadius = '10px';
                settingsButton.style.background = '#ffffff';
                settingsButton.style.color = '#0f172a';
                settingsButton.style.fontWeight = '700';
                settingsButton.style.fontSize = '14px';
                settingsButton.style.cursor = 'pointer';
                settingsButton.style.boxShadow = '0 6px 18px rgba(15, 23, 42, 0.16)';
                settingsButton.addEventListener('click', () => {
                    if (window.reservationClient && typeof window.reservationClient.openSettings === 'function') {
                        window.reservationClient.openSettings();
                    }
                });
                document.body.appendChild(settingsButton);
            }

            if (!isPrintLikePage) {
                if (existingBackButton) existingBackButton.remove();
                return;
            }

            if (existingBackButton) {
                return;
            }

            const btn = document.createElement('button');
            btn.id = 'hotel-client-back-btn';
            btn.type = 'button';
            btn.textContent = '<';
            btn.style.position = 'fixed';
            btn.style.top = '14px';
            btn.style.left = '14px';
            btn.style.zIndex = '2147483647';
            btn.style.padding = '10px 14px';
            btn.style.border = '1px solid rgba(15, 23, 42, 0.18)';
            btn.style.borderRadius = '10px';
            btn.style.background = '#ffffff';
            btn.style.color = '#0f172a';
            btn.style.fontWeight = '700';
            btn.style.fontSize = '14px';
            btn.style.cursor = 'pointer';
            btn.style.boxShadow = '0 6px 18px rgba(15, 23, 42, 0.16)';

            btn.addEventListener('click', () => {
                if (window.history.length > 1) {
                    window.history.back();
                    return;
                }

                window.location.href = '/resido';
            });

            document.body.appendChild(btn);
        })();
    """
}
