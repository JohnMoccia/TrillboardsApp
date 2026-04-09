package com.curb.trillboards;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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

    private static final String TAG       = "CurbAds";
    private static final String DEVICE_ID = "P_691cbdd4b3117cb1b692f4ae";
    private static final String SCREEN_ID = "69a076ad2ccbba67a5ba1c1e";
    private static final String KV_URL    = "https://trillboards-proxy.johnm-af2.workers.dev/log-impression";
    private static final String KV_SECRET = "curb2026";
    private static final String EMBED_URL = "https://screen.trillboards.com?fp=" + DEVICE_ID;

    // WebView is ALWAYS persistent — never destroyed (wiping it kills IndexedDB content cache)
    private WebView  adWebView   = null;
    private boolean  adShowing   = false;
    private Handler  mainHandler = new Handler(Looper.getMainLooper());
    private Runnable adWatchdog  = null;

    // SOV tracking — set by OverlayEventBus events
    private boolean programmaticFillThisCycle = false;
    private String  adSourceThisCycle         = "content_cache";

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

        requestBatteryOptimizationExemption();
        loadUrl(launchUrl);
    }

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
        // Always create the WebView — keep persistent to preserve IndexedDB content cache
        mainHandler.postDelayed(() -> createAdWebView(), 3000);
    }

    @Override
    public void onResume() {
        super.onResume();
        this.appView.handleResume(true);
        if (!pendingImpressions.isEmpty()) {
            Log.i(TAG, "App resumed — flushing " + pendingImpressions.size() + " pending impressions");
            flushPendingImpressions();
        }
    }
    @Override public void onPause()   { super.onPause();  this.appView.handlePause(true); }
    @Override public void onDestroy() {
        Intent si = new Intent(this, AdService.class);
        stopService(si);
        super.onDestroy();
    }

    // ── CurbBridge — exposed to index.html ───────────────────────────────────
    public class CurbBridge {

        @JavascriptInterface
        public void showAd() {
            Log.i(TAG, "showAd() called from JS");
            mainHandler.post(() -> launchAdOverlay());
        }

        @JavascriptInterface
        public void closeAd() {
            Log.i(TAG, "closeAd() called from JS");
            mainHandler.post(() -> dismissAdOverlay(false, "manual", 0));
        }

        @JavascriptInterface
        public void setPlayerMode(String mode) {
            // Mode switching no longer destroys WebView — content cache must be preserved
            // In both modes WebView stays alive, just shown/hidden differently
            Log.i(TAG, "setPlayerMode: " + mode + " (WebView always persistent)");
        }
    }

    // ── Create persistent ad WebView ─────────────────────────────────────────
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
        // Limit renderer memory to prevent OOM crash on SM-T500 (2GB RAM)
        // Bugreport showed heap: 70/70 before native crash
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            adWebView.setRendererPriorityPolicy(
                android.webkit.WebView.RENDERER_PRIORITY_BOUND, true);
        }

        // Inject OverlayEventBus bridge — Sneh's official event API
        adWebView.addJavascriptInterface(new OverlayEventBridge(), "OverlayEventBridge");
        adWebView.setWebChromeClient(new WebChromeClient());
        adWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "Ad WebView loaded: " + url);
                // Subscribe to OverlayEventBus after page loads
                mainHandler.postDelayed(() -> subscribeToOverlayEvents(view), 2000);
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
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
                        Log.i(TAG, "Proxied " + url.substring(url.lastIndexOf("/") + 1) + " → " + status);
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
        android.view.ViewGroup decorView = (android.view.ViewGroup) getWindow().getDecorView();
        decorView.addView(adWebView, params);
        adWebView.loadUrl(EMBED_URL);
        Log.i(TAG, "Ad WebView loaded — persistent, IndexedDB content cache preserved");
    }

    // ── Subscribe to Sneh's OverlayEventBus ──────────────────────────────────
    // This is the official API — replaces the poll-based video detection
    private void subscribeToOverlayEvents(WebView view) {
        String js =
            "(function() {" +
            "  try {" +
            "    if (!window.OverlayEventBus) {" +
            "      console.log('[CurbAds] OverlayEventBus not ready — retrying');" +
            "      setTimeout(arguments.callee, 1000);" +
            "      return;" +
            "    }" +

            "    window.OverlayEventBus.on('ad:started', function(d) {" +
            "      OverlayEventBridge.onEvent(JSON.stringify({type:'ad:started'," +
            "        source:d&&d.source?d.source:'unknown'," +
            "        isProgrammatic:d&&d.isProgrammatic?true:false}));" +
            "    });" +
            "    window.OverlayEventBus.on('ad:completed', function(d) {" +
            "      OverlayEventBridge.onEvent(JSON.stringify({type:'ad:completed'," +
            "        adId:d&&d.adId?d.adId:''," +
            "        adType:d&&d.adType?d.adType:'unknown'," +
            "        duration:d&&d.duration?d.duration:30}));" +
            "    });" +
            "    window.OverlayEventBus.on('ad:error', function(d) {" +
            "      OverlayEventBridge.onEvent(JSON.stringify({type:'ad:error'," +
            "        error:d&&d.error?d.error:''," +
            "        waterfallExhausted:d&&d.waterfallExhausted?true:false}));" +
            "    });" +
            "    console.log('[CurbAds] OverlayEventBus subscribed');" +
            "  } catch(e) {" +
            "    console.log('[CurbAds] OverlayEventBus subscribe failed: ' + e.message);" +
            "  }" +
            "})();";
        view.evaluateJavascript(js, null);
        Log.i(TAG, "OverlayEventBus subscription injected");


    }

    // ── OverlayEventBridge — receives events from OverlayEventBus ────────────
    public class OverlayEventBridge {

        @JavascriptInterface
        public void onEvent(String eventJson) {
            try {
                JSONObject evt = new JSONObject(eventJson);
                String type = evt.optString("type", "");
                Log.i(TAG, "OverlayEvent: " + eventJson);

                switch (type) {
                    case "ad:started":
                        programmaticFillThisCycle = evt.optBoolean("isProgrammatic", false);
                        adSourceThisCycle = evt.optString("source", "unknown");
                        Log.i(TAG, "AD STARTED — source:" + adSourceThisCycle
                            + " programmatic:" + programmaticFillThisCycle);
                        // Cancel 2-min safety watchdog, start 45s completion watchdog
                        if (adWatchdog != null) mainHandler.removeCallbacks(adWatchdog);
                        adWatchdog = () -> {
                            Log.w(TAG, "Ad completion watchdog — dismissing");
                            dismissAdOverlay(true, adSourceThisCycle, 30);
                        };
                        mainHandler.postDelayed(adWatchdog, 45000);
                        // Query Sneh's API for full ad detail — adSource, adType, currentAdId
                        queryAdDetail();
                        // Tell our JS ad started
                        if (appView != null) {
                            runOnUiThread(() -> appView.getEngine().evaluateJavascript(
                                "adStartedThisCycle=true;", null));
                        }
                        break;

                    case "ad:completed":
                        int dur = evt.optInt("duration", 30);
                        String adType = evt.optString("adType", adSourceThisCycle);
                        String adId = evt.optString("adId", "");
                        Log.i(TAG, "AD COMPLETED — adType:" + adType + " adId:" + adId + " duration:" + dur + "s");
                        if (adWatchdog != null) mainHandler.removeCallbacks(adWatchdog);
                        // NOTE: masterAdTimer NOT cleared — letting their auto-cycle run
                        // to ensure content cache prefetch completes. SOV control via checkAndShowAds only.
                        // TODO: re-enable once cache is warm and ad:completed fires reliably
                        final int durFinal = dur;
                        final String typeFinal = adType;
                        mainHandler.post(() -> dismissAdOverlay(true, typeFinal, durFinal));
                        break;

                    case "ad:error":
                        boolean exhausted = evt.optBoolean("waterfallExhausted", false);
                        String error = evt.optString("error", "");
                        Log.i(TAG, "AD ERROR — " + error + " exhausted:" + exhausted);
                        if (exhausted) {
                            // Waterfall exhausted — content cache will take over in ~1s
                            // Don't dismiss — wait for ad:started from cache
                            // Set a fallback watchdog in case cache also fails
                            if (adWatchdog != null) mainHandler.removeCallbacks(adWatchdog);
                            adWatchdog = () -> {
                                Log.w(TAG, "Post-exhaustion watchdog — no cache fill, dismissing");
                                // Log as no-fill request
                                notifyJsNoFill();
                                dismissAdOverlay(false, "no_fill", 0);
                            };
                            mainHandler.postDelayed(adWatchdog, 15000);
                        }
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "OverlayEventBridge error: " + e.getMessage());
            }
        }
    }

    // ── Launch ad overlay — show WebView + trigger checkAndShowAds ───────────
    private void launchAdOverlay() {
        if (adShowing) return;
        adShowing = true;
        programmaticFillThisCycle = false;
        adSourceThisCycle = "content_cache";
        Log.i(TAG, "Showing ad overlay");

        if (adWebView == null) createAdWebView();

        adWebView.setVisibility(android.view.View.VISIBLE);
        adWebView.bringToFront();
        // Immersive fullscreen — hides nav bar, fixes audio-only issue
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            adWebView.setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        // Use Sneh's official trigger API — window.minimalAdvertisementOverlay.checkAndShowAds(true)
        // Retry up to 10 times with 1s delay if not ready yet
        adWebView.evaluateJavascript(
            "(function tryTrigger(attempt){" +
            "  try{" +
            "    var mao = window.minimalAdvertisementOverlay;" +
            "    if(mao && typeof mao.checkAndShowAds === 'function'){" +
            "      mao.checkAndShowAds(true);" +
            "      console.log('[CurbAds] checkAndShowAds(true) called (attempt '+attempt+')');" +
            "    } else if(attempt < 10){" +
            "      console.log('[CurbAds] checkAndShowAds not ready — retrying in 1s (attempt '+attempt+')');" +
            "      setTimeout(function(){ tryTrigger(attempt+1); }, 1000);" +
            "    } else {" +
            "      console.log('[CurbAds] checkAndShowAds unavailable after 10 attempts — player auto-cycling');" +
            "    }" +
            "  }catch(e){console.log('[CurbAds] checkAndShowAds error: '+e.message);}" +
            "})(1)", null);

        // Safety watchdog — 5 min max, gives content cache time to play
        // completed=false — only real ad:completed events count as fills
        adWatchdog = () -> {
            Log.w(TAG, "Safety watchdog fired — no ad:completed received, not counting as fill");
            dismissAdOverlay(false, "no_fill", 0);
        };
        mainHandler.postDelayed(adWatchdog, 300000);
    }

    private void notifyJsNoFill() {
        if (appView != null) {
            runOnUiThread(() -> appView.getEngine().evaluateJavascript(
                "if(typeof onAdOverlayClosed==='function') onAdOverlayClosed(false);", null));
        }
    }

    // ── Query ad detail from Sneh's API ─────────────────────────────────────
    // Gets adSource (adipolo/vidverto/etc), adType (ima/paid/self_promo), currentAdId
    private void queryAdDetail() {
        if (adWebView == null) return;
        mainHandler.post(() -> adWebView.evaluateJavascript(
            "JSON.stringify({" +
            "  adSource: window.minimalAdvertisementOverlay && window.minimalAdvertisementOverlay.imaAdManager" +
            "    ? window.minimalAdvertisementOverlay.imaAdManager.adSource : null," +
            "  adType: window.minimalAdvertisementOverlay && window.minimalAdvertisementOverlay.imaAdManager" +
            "    ? window.minimalAdvertisementOverlay.imaAdManager.adType : null," +
            "  currentAdId: window.minimalAdvertisementOverlay && window.minimalAdvertisementOverlay.imaAdManager" +
            "    ? window.minimalAdvertisementOverlay.imaAdManager.currentAdId : null" +
            "})",
            result -> {
                try {
                    if (result == null || result.equals("null")) return;
                    // Strip JS string quotes
                    String json = result.startsWith("\"") ? result.substring(1, result.length()-1).replace("\\\"", "\"") : result;
                    JSONObject detail = new JSONObject(json);
                    String adSource  = detail.optString("adSource",  "unknown");
                    String adType    = detail.optString("adType",    "unknown");
                    String currentAdId = detail.optString("currentAdId", "");
                    adSourceThisCycle = adSource.isEmpty() ? adSourceThisCycle : adSource;
                    Log.i(TAG, "Ad detail — adSource:" + adSource
                        + " adType:" + adType + " adId:" + currentAdId);
                    // Notify our JS with full detail for KV tracking
                    if (appView != null) {
                        final String src = adSource;
                        final String typ = adType;
                        final String aid = currentAdId;
                        runOnUiThread(() -> appView.getEngine().evaluateJavascript(
                            "if(typeof onAdDetail==='function') onAdDetail('" + src
                                + "','" + typ + "','" + aid + "');", null));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "queryAdDetail parse error: " + e.getMessage());
                }
            }
        ));
    }

    // ── Dismiss overlay ───────────────────────────────────────────────────────
    private void dismissAdOverlay(boolean completed, String source, int durationSec) {
        if (!adShowing) return;
        adShowing = false;
        Log.i(TAG, "Hiding ad overlay completed=" + completed + " source=" + source);

        if (adWebView != null) {
            // Pause videos but DON'T destroy WebView — content cache must stay alive
            adWebView.evaluateJavascript(
                "(function(){try{" +
                "  var vids=document.querySelectorAll('video');" +
                "  for(var i=0;i<vids.length;i++){vids[i].pause();}" +
                "}catch(e){}})()", null);
            adWebView.setVisibility(android.view.View.GONE);
        }

        // Notify our JS with source info for accurate KV tracking
        if (this.appView != null) {
            final boolean comp = completed;
            final String src = source;
            final int dur = durationSec;
            runOnUiThread(() -> this.appView.getEngine().evaluateJavascript(
                "if(typeof onAdOverlayClosed==='function') onAdOverlayClosed(" + comp
                    + ",'" + src + "'," + dur + ");",
                null));
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
                synchronized (pendingImpressions) { pendingImpressions.add(body); }
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
        for (JSONObject imp : toSend) { sendToKV(imp); }
    }
}
