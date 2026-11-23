package com.chra4.camera;

import android.content.Context;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

import java.util.Collections;

/*
 کامێرا — Android shell for camera.4chra.com
 Design & Development Hemn A.Muhammad

 The page draws the map and beeps while it is on screen. KameraGuard (native) takes
 over the alerts while the app is in the background or the screen is off, exactly like
 KameraGuard.swift does on iPhone. A small script injected into the page reports
 Start / Stop and the settings to KameraGuard and keeps the page quiet in the background,
 so an alert never sounds twice.
*/
public class MainActivity extends BridgeActivity {

    private boolean hookAdded = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installHook();
    }

    private void installHook() {
        if (hookAdded || bridge == null) return;
        WebView wv = bridge.getWebView();
        if (wv == null) return;
        hookAdded = true;
        KameraGuard.get(this);
        wv.addJavascriptInterface(new KameraJs(getApplicationContext()), "KameraAndroid");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, KameraGuard.HOOK_JS, Collections.singleton("https://camera.4chra.com"));
            } catch (Exception ignored) {
                // the page-loaded listener below still installs it
            }
        }
        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView view) {
                view.evaluateJavascript(KameraGuard.HOOK_JS, null); // does nothing if already installed
                view.evaluateJavascript(pausedJs(!KameraGuard.get(view.getContext()).isVisible()), null);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        installHook();
        KameraGuard.get(this).setVisible(true);
        tellPage(false);
    }

    @Override
    public void onPause() {
        KameraGuard.get(this).setVisible(false);
        tellPage(true);
        super.onPause();
    }

    static String pausedJs(boolean paused) {
        return "window.__kgSetPaused && window.__kgSetPaused(" + paused + ");";
    }

    private void tellPage(boolean paused) {
        if (bridge == null) return;
        final WebView wv = bridge.getWebView();
        if (wv == null) return;
        wv.post(() -> wv.evaluateJavascript(pausedJs(paused), null));
    }

    /** Messages from the injected page script (called on a WebView background thread). */
    public static final class KameraJs {
        private final Context ctx;

        KameraJs(Context ctx) {
            this.ctx = ctx;
        }

        @JavascriptInterface
        public void post(String json) {
            KameraGuard.get(ctx).onMessage(json);
        }
    }
}
