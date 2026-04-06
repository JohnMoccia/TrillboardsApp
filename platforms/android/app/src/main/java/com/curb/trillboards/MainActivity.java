package com.curb.trillboards;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.PowerManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.apache.cordova.CordovaActivity;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends CordovaActivity {

    private static final String TAG        = "CurbAds";
    private static final String DEVICE_ID  = "P_691cbdd4b3117cb1b692f4ae";
    private static final String SCREEN_ID  = "69a076ad2ccbba67a5ba1c1e";
    private static final String KV_URL     = "https://trillboards-proxy.johnm-af2.workers.dev/log-impression";
    private static final String KV_SECRET  = "curb2026";
    private static final String EMBED_URL  = "https://screen.trillboards.com?fp=" + DEVICE_ID;

    private WebView  adWebView   = null;
    private boolean  adShowing   = false;
    private Handler  mainHandler = new Handler(Looper.getMainLooper());
    private Runnable adWatchdog  = null;

    // Player mode — set by JS via CurbBridge.setPlayerMode()
    // Defaults to embed for safety — native mode must be explicitly set
    private String playerMode = "embed";

    // Impression queue — stored locally, batch sent on app resume
    private final List<JSONObject> pendingImpressions = new ArrayList<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.getBoolean("cdvStartInBackground", false)) {
            moveTaskToBack(true);
        }

        Intent serviceIntent = new Intent(this, AdService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Request battery optimization exemption — prevents Samsung from killing
        // the foreground service during extended unattended operation
        requestBatteryOptimizationExemption();

        loadUrl(launchUrl);
    }

    // Request battery optimization exemption so Android/Samsung doesn't kill us
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    android.content.Intent intent = new android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    Log.i(TAG, "Requesting battery optimization exemption");
                } else {
                    Log.i(TAG, "Already exempt from battery optimization");
                }
            } catch (Exception e) {
                Log.w(TAG, "Battery optimization request failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void init() {
        super.init();
        if (this.appView != null && this.appView.getView() instanceof WebView) {
            WebView wv = (WebView) this.appView.getView();
            wv.addJavascriptInterface(new CurbBridge(), "CurbBridge");
            Log.i(TAG, "CurbBridge injected");
        }
        // Pre-warm deferred — JS will call setPlayerMode first, then we decide
        // whether to create the ad WebView based on player mode
        mainHandler.postDelayed(() -> {
            if ("embed".equals(playerMode)) {
                createAdWebView();
            } else {
                Log.i(TAG, "Native mode — skipping embed WebView creation");
            }
        }, 3000);
    }

    @Override
    public void onResume() {
        super.onResume();
        this.appView.handleResume(true);
        // Flush any pending impressions when app comes back into focus
        if (!pendingImpressions.isEmpty()) {
            Log.i(TAG, "App resumed — flushing " + pendingImpressions.size() + " pending impressions");
            flushPendingImpressions();
        }
    }
    @Override public void onPause()  { super.onPause();  this.appView.handlePause(true);  }
    @Override public void onDestroy() {
        Intent si = new Intent(this, AdService.class);
        stopService(si);
        super.onDestroy();
    }

    // ── Bridge exposed to our index.html ─────────────────────────────────────
    public class CurbBridge {

        @JavascriptInterface
        public void showAd() {
            Log.i(TAG, "showAd() called from JS");
            mainHandler.post(() -> launchAdOverlay());
        }

        @JavascriptInterface
        public void closeAd() {
            Log.i(TAG, "closeAd() called from JS");
            mainHandler.post(() -> dismissAdOverlay(false));
        }

        // Called by index.html as soon as player mode is known from KV config
        // Allows Java to decide whether to create the embed WebView
        @JavascriptInterface
        public void setPlayerMode(String mode) {
            Log.i(TAG, "setPlayerMode: " + mode);
            playerMode = mode;
            mainHandler.post(() -> {
                if ("embed".equals(mode)) {
                    // Embed mode — ensure WebView exists
                    if (adWebView == null) createAdWebView();
                } else {
                    // Native mode — destroy embed WebView if it exists
                    if (adWebView != null) {
                        Log.i(TAG, "Native mode — destroying embed WebView");
                        adWebView.stopLoading();
                        adWebView.loadUrl("about:blank");
                        adWebView.setVisibility(android.view.View.GONE);
                        adWebView.destroy();
                        adWebView = null;
                    }
                }
            });
        }
    }

    // ── Create persistent ad WebView (called once at startup) ──────────────
    private void createAdWebView() {
        if (adWebView != null) return;
        Log.i(TAG, "Creating persistent ad WebView");

        adWebView = new WebView(this);
        adWebView.setBackgroundColor(Color.BLACK);
        adWebView.setVisibility(android.view.View.GONE);

        WebSettings ws = adWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ws.setSafeBrowsingEnabled(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            ws.setAllowUniversalAccessFromFileURLs(true);
            ws.setAllowFileAccessFromFileURLs(true);
        }

        adWebView.addJavascriptInterface(new TrillboardsBridge(), "TrillboardsBridge");
        adWebView.setWebChromeClient(new WebChromeClient());
        adWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "Ad overlay page loaded: " + url);
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("check-screen") || url.contains("alot-advertisement-list")) {
                    try {
                        java.net.URL u = new java.net.URL(url);
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("Origin", "https://screen.trillboards.com");
                        conn.setRequestProperty("Referer", "https://screen.trillboards.com/");
                        for (java.util.Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                            try { conn.setRequestProperty(h.getKey(), h.getValue()); } catch (Exception ignored) {}
                        }
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(8000);
                        conn.connect();
                        String rawType = conn.getContentType();
                        final String contentType = (rawType != null) ? rawType : "application/json";
                        int status = conn.getResponseCode();
                        Log.i(TAG, "Proxied " + url.substring(url.lastIndexOf("/")+1) + " → " + status);
                        if (url.contains("check-screen") && status == 200) {
                            mainHandler.postDelayed(() -> injectConsoleInterceptor(view), 1500);
                        }
                        java.io.InputStream is = status >= 200 && status < 300
                            ? conn.getInputStream() : conn.getErrorStream();
                        return new android.webkit.WebResourceResponse(contentType, "UTF-8", status,
                            status >= 200 && status < 300 ? "OK" : "Error",
                            new java.util.HashMap<String, String>() {{
                                put("Access-Control-Allow-Origin", "*");
                                put("Access-Control-Allow-Headers", "x-device-token, content-type, authorization, origin");
                                put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                                put("Content-Type", contentType);
                            }}, is);
                    } catch (Exception e) {
                        Log.e(TAG, "Proxy failed: " + e.getMessage());
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        addContentView(adWebView, params);
        adWebView.loadUrl(EMBED_URL);
        Log.i(TAG, "Ad WebView loaded — will persist across cycles");
        mainHandler.postDelayed(() -> injectConsoleInterceptor(adWebView), 4000);
    }

    // ── Launch ad overlay — just show the persistent WebView ─────────────────
    private void launchAdOverlay() {
        if (adShowing) return;
        adShowing = true;
        Log.i(TAG, "Showing ad overlay");

        if (adWebView == null) createAdWebView();

        adWebView.setVisibility(android.view.View.VISIBLE);
        adWebView.bringToFront();

        adWebView.evaluateJavascript("window._cs=false;window._cc=false;window._cn=false;", null);

        adWebView.evaluateJavascript(
            "(function(){" +
            "  try{" +
            "    var vids=document.querySelectorAll('video');" +
            "    for(var i=0;i<vids.length;i++){" +
            "      if(vids[i].paused) vids[i].play().catch(function(){});" +
            "    }" +
            "  }catch(e){}" +
            "})()", null);

        adWatchdog = () -> {
            Log.w(TAG, "Ad watchdog fired — treating as completed (ad played)");
            dismissAdOverlay(true);
        };
        mainHandler.postDelayed(adWatchdog, 120000);
    }

    // ── Inject JS console interceptor to detect ad events ────────────────────
    private void injectConsoleInterceptor(WebView view) {
        String js =
            "window._cs=false;window._cc=false;window._cn=false;\n" +
            "if(window._pollTimer)clearInterval(window._pollTimer);\n" +
            "window._pollTimer=setInterval(function(){\n" +
            "  try{\n" +
            "    var vids=[];\n" +
            "    vids=vids.concat(Array.from(document.querySelectorAll('video')));\n" +
            "    try{var frames=document.querySelectorAll('iframe');for(var i=0;i<frames.length;i++){try{vids=vids.concat(Array.from(frames[i].contentDocument.querySelectorAll('video')));}catch(e){}}}catch(e){}\n" +
            "    var playing=vids.some(function(v){return !v.paused&&!v.ended&&v.currentTime>0&&v.readyState>2;});\n" +
            "    var ended=vids.some(function(v){return v.ended;});\n" +
            "    if(playing&&!window._cs){window._cs=true;try{CurbAdEvents.onAdStarted();}catch(e){}}\n" +
            "    if(ended&&window._cs&&!window._cc){window._cc=true;window._cs=false;try{CurbAdEvents.onAdComplete();}catch(e){}}\n" +
            "  }catch(e){}\n" +
            "},1000);\n";

        view.addJavascriptInterface(new AdEventsBridge(), "CurbAdEvents");
        view.evaluateJavascript(js, null);
        Log.i(TAG, "Console interceptor injected");
    }

    // ── Ad events from injected JS ────────────────────────────────────────────
    public class AdEventsBridge {

        @JavascriptInterface
        public void onAdStarted() {
            Log.i(TAG, "AD STARTED via console intercept");
            if (adWatchdog != null) {
                mainHandler.removeCallbacks(adWatchdog);
                adWatchdog = () -> {
                    Log.w(TAG, "Ad completion watchdog — dismissing");
                    dismissAdOverlay(true);
                };
                mainHandler.postDelayed(adWatchdog, 45000);
            }
            if (appView != null) {
                runOnUiThread(() -> appView.getEngine().evaluateJavascript(
                    "adStartedThisCycle=true;", null));
            }
        }

        @JavascriptInterface
        public void onAdComplete() {
            Log.i(TAG, "AD COMPLETE via console intercept");
            if (adWatchdog != null) mainHandler.removeCallbacks(adWatchdog);
            mainHandler.post(() -> dismissAdOverlay(true));
        }

        @JavascriptInterface
        public void onNoFill() {
            Log.i(TAG, "NO FILL via console — fallback cache loads in ~1s");
            if (adWatchdog != null) mainHandler.removeCallbacks(adWatchdog);
            adWatchdog = () -> {
                Log.w(TAG, "Post-no-fill watchdog — dismissing overlay");
                dismissAdOverlay(false);
            };
            mainHandler.postDelayed(adWatchdog, 15000);
        }
    }

    // ── Trillboards SDK bridge ────────────────────────────────────────────────
    public class TrillboardsBridge {

        @JavascriptInterface
        public void onEvent(String eventJson) {
            try {
                Log.i(TAG, "TrillboardsBridge: " + eventJson);
                JSONObject evt  = new JSONObject(eventJson);
                String type     = evt.optString("event", "");
                JSONObject data = evt.optJSONObject("data");
                if (data == null) data = new JSONObject();

                switch (type) {
                    case "programmatic_started":
                    case "ad_started":
                        Log.i(TAG, "AD STARTED — " + data.optString("source","?"));
                        if (adWatchdog != null) mainHandler.removeCallbacks(adWatchdog);
                        break;

                    case "programmatic_ended":
                    case "ad_ended":
                        int dur = data.optInt("duration", 30);
                        String src = data.optString("source", "trillboards");
                        Log.i(TAG, "AD ENDED — " + src + " " + dur + "s");
                        if (adWatchdog != null) mainHandler.removeCallbacks(adWatchdog);
                        mainHandler.post(() -> dismissAdOverlay(true));
                        break;

                    case "programmatic_no_fill":
                        Log.i(TAG, "NO FILL — waiting for fallback cached content");
                        if (adWatchdog != null) {
                            mainHandler.removeCallbacks(adWatchdog);
                            adWatchdog = () -> {
                                Log.w(TAG, "No fill watchdog — dismissing overlay");
                                dismissAdOverlay(false);
                            };
                            mainHandler.postDelayed(adWatchdog, 15000);
                        }
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "TrillboardsBridge error: " + e.getMessage());
            }
        }
    }

    // ── Dismiss overlay ───────────────────────────────────────────────────────
    private void dismissAdOverlay(boolean completed) {
        if (!adShowing) return;
        adShowing = false;
        Log.i(TAG, "Hiding ad overlay completed=" + completed);

        if (adWebView != null) {
            adWebView.evaluateJavascript(
                "(function(){" +
                "  try{" +
                "    var vids=document.querySelectorAll('video');" +
                "    for(var i=0;i<vids.length;i++){vids[i].pause();}" +
                "    if(window.__overlay && window.__overlay.pause) window.__overlay.pause();" +
                "    if(window.overlayInstance && window.overlayInstance.pause) window.overlayInstance.pause();" +
                "  }catch(e){}" +
                "})()", null);
            adWebView.setVisibility(android.view.View.GONE);
        }

        if (this.appView != null) {
            final boolean comp = completed;
            runOnUiThread(() -> {
                this.appView.getEngine().evaluateJavascript(
                    "if(typeof onAdOverlayClosed==='function') onAdOverlayClosed(" + comp + ");",
                    null
                );
            });
        }
    }

    // ── KV POST ───────────────────────────────────────────────────────────────
    private void postToKV(final String type, final String source,
                          final int durationSec, final boolean completed) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject body = new JSONObject();
            body.put("type",        type);
            body.put("screen_id",   SCREEN_ID);
            body.put("device_id",   DEVICE_ID);
            body.put("source",      source);
            body.put("duration_ms", durationSec * 1000);
            body.put("completed",   completed);
            body.put("timestamp",   sdf.format(new Date()));

            if (completed && "ad_ended".equals(type)) {
                synchronized (pendingImpressions) {
                    pendingImpressions.add(body);
                }
                Log.i(TAG, "Queued completed impression — total pending: " + pendingImpressions.size());
            } else {
                sendToKV(body);
            }
        } catch (Exception e) {
            Log.e(TAG, "postToKV error: " + e.getMessage());
        }
    }

    private void sendToKV(final JSONObject body) {
        new Thread(() -> {
            try {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                HttpURLConnection conn = (HttpURLConnection) new URL(KV_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Proxy-Secret", KV_SECRET);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
                Log.i(TAG, "KV POST → HTTP " + conn.getResponseCode());
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "KV POST failed: " + e.getMessage());
            }
        }).start();
    }

    private void flushPendingImpressions() {
        List<JSONObject> toSend;
        synchronized (pendingImpressions) {
            toSend = new ArrayList<>(pendingImpressions);
            pendingImpressions.clear();
        }
        Log.i(TAG, "Flushing " + toSend.size() + " impressions to KV");
        for (JSONObject imp : toSend) {
            sendToKV(imp);
        }
    }
}
