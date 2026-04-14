package com.curb.trillboards;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ima.ImaAdsLoader;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Native IMA SDK ad player for Trillboards Partner API integration.
 * Uses Media3 ExoPlayer + ImaAdsLoader per the Trillboards Partner Integration Guide.
 *
 * Key rules from the guide:
 * - pt=partner_api (NOT embed_webview) — server-side wrapper resolution
 * - NO setAdMediaMimeTypes() — kills VidVerto/VPAID
 * - Pass full unmodified VAST XML to IMA
 * - Events MUST include request_id + variant_name
 * - Heartbeat every 60s with real device.ua from WebSettings
 */
public class NativeAdPlayer {

    private static final String TAG = "NativeAdPlayer";

    // Trillboards API base
    private static final String API_BASE = "https://api.trillboards.com";

    // Device + screen identity (same as MainActivity)
    private static final String DEVICE_ID = "P_691cbdd4b3117cb1b692f4ae";
    private static final String SCREEN_ID = "69a076ad2ccbba67a5ba1c1e";

    // KV proxy for dashboard tracking
    private static final String KV_URL    = "https://trillboards-proxy.johnm-af2.workers.dev/log-impression";
    private static final String KV_SECRET = "curb2026";

    private static final String AGENT_VERSION = "1.0.0";
    private static final long HEARTBEAT_INTERVAL_MS = 60000;

    // ── Waterfall source from /ads endpoint ──
    public static class WaterfallSource {
        String name;        // variant_name — REQUIRED for event reporting
        String vastUrl;     // full URL with pt=partner_api
        String requestId;   // from /ads response — REQUIRED for event reporting
        int timeoutMs;
        int priority;
    }

    // ── Callback interface to MainActivity ──
    public interface NativeAdListener {
        void onAdStarted(String source, String requestId);
        void onAdCompleted(String source, int durationSec, String requestId);
        void onNoFill(String lastSource);
        void onError(String source, String error);
    }

    private final Activity activity;
    private final FrameLayout container;
    private final NativeAdListener listener;
    private final Handler mainHandler;
    private final OkHttpClient httpClient;

    // Device identity — captured once at init
    private final String realUserAgent;
    private final String deviceMake;
    private final String deviceModel;
    private final String deviceOs;
    private final String deviceOsv;

    // ExoPlayer + IMA
    private ExoPlayer player;
    private PlayerView playerView;
    private ImaAdsLoader imaAdsLoader;

    // Current ad state
    private String currentRequestId;
    private String currentVariantName;
    private String currentImpressionId;
    private long adStartTimeMs;
    private boolean adPlaying = false;

    // Heartbeat
    private final Runnable heartbeatRunnable;
    private boolean heartbeatRunning = false;

    public NativeAdPlayer(Activity activity, FrameLayout container, NativeAdListener listener) {
        this.activity = activity;
        this.container = container;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());

        // HTTP client with reasonable timeouts
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build();

        // ── Capture real device identity (done ONCE) ──
        // device.ua — THE most important field per the guide
        String ua;
        try {
            ua = WebSettings.getDefaultUserAgent(activity);
        } catch (Exception e) {
            ua = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; "
                + Build.MODEL + " Build/" + Build.DISPLAY + "; wv) AppleWebKit/537.36";
            Log.w(TAG, "WebSettings.getDefaultUserAgent failed, using fallback UA");
        }
        this.realUserAgent = ua;

        // device.make — title case (e.g., "Samsung" not "samsung")
        String rawMake = Build.MANUFACTURER;
        this.deviceMake = rawMake.substring(0, 1).toUpperCase() + rawMake.substring(1).toLowerCase();

        // device.model — real chipset from Build.BOARD (NOT Build.MODEL which is SKU like "SM-T500")
        // Per guide: "Do NOT send your internal SKU"
        this.deviceModel = Build.BOARD;

        // device.os — proper case + version
        this.deviceOs = "Android " + Build.VERSION.RELEASE;

        // device.osv — API level as string (e.g., "30" for Android 11)
        this.deviceOsv = String.valueOf(Build.VERSION.SDK_INT);

        Log.i(TAG, "Device identity captured:"
            + " make=" + deviceMake
            + " model=" + deviceModel
            + " os=" + deviceOs
            + " osv=" + deviceOsv
            + " ua=" + realUserAgent.substring(0, Math.min(80, realUserAgent.length())) + "...");

        // ── Create PlayerView ──
        playerView = new PlayerView(activity);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(playerView);

        // ── Heartbeat timer ──
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat();
                if (heartbeatRunning) {
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
                }
            }
        };
    }

    /** Start the 60s heartbeat cycle. Call once when entering native mode. */
    public void startHeartbeat() {
        if (heartbeatRunning) return;
        heartbeatRunning = true;
        Log.i(TAG, "Starting heartbeat (60s interval)");
        // Fire immediately, then every 60s
        mainHandler.post(heartbeatRunnable);
    }

    /** Stop heartbeat. Call when switching away from native mode. */
    public void stopHeartbeat() {
        heartbeatRunning = false;
        mainHandler.removeCallbacks(heartbeatRunnable);
        Log.i(TAG, "Heartbeat stopped");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HEARTBEAT — POST /v1/partner/device/{deviceId}/heartbeat
    // ══════════════════════════════════════════════════════════════════════════

    private void sendHeartbeat() {
        new Thread(() -> {
            try {
                JSONObject device = new JSONObject();
                device.put("make", deviceMake);
                device.put("model", deviceModel);
                device.put("os", deviceOs);
                device.put("osv", deviceOsv);
                device.put("ua", realUserAgent);

                JSONObject app = new JSONObject();
                app.put("agentVersion", AGENT_VERSION);

                JSONObject body = new JSONObject();
                body.put("device", device);
                body.put("application", app);

                String url = API_BASE + "/v1/partner/device/" + DEVICE_ID + "/heartbeat";
                Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                    .header("User-Agent", realUserAgent)
                    .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    Log.i(TAG, "Heartbeat → HTTP " + resp.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "Heartbeat failed: " + e.getMessage());
            }
        }).start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AD REQUEST — GET /v1/partner/device/{deviceId}/ads
    // ══════════════════════════════════════════════════════════════════════════

    /** Main entry point — fetch waterfall sources and walk them. */
    public void requestAndPlayAd() {
        if (adPlaying) {
            Log.w(TAG, "requestAndPlayAd() — already playing, ignoring");
            return;
        }
        adPlaying = true;
        Log.i(TAG, "Requesting ad waterfall...");

        new Thread(() -> {
            try {
                List<WaterfallSource> sources = fetchAdSources();
                if (sources == null || sources.isEmpty()) {
                    Log.w(TAG, "No waterfall sources returned from /ads");
                    adPlaying = false;
                    mainHandler.post(() -> listener.onNoFill("none"));
                    return;
                }

                Log.i(TAG, "Got " + sources.size() + " waterfall sources");
                for (WaterfallSource s : sources) {
                    Log.i(TAG, "  [" + s.priority + "] " + s.name
                        + " reqId=" + s.requestId
                        + " timeout=" + s.timeoutMs + "ms"
                        + " pt=" + (s.vastUrl.contains("pt=partner_api") ? "partner_api" : "WRONG"));
                }

                // Walk waterfall on background thread, switch to UI for playback
                walkWaterfall(sources, 0);

            } catch (Exception e) {
                Log.e(TAG, "requestAndPlayAd failed: " + e.getMessage());
                adPlaying = false;
                mainHandler.post(() -> listener.onError("unknown", e.getMessage()));
            }
        }).start();
    }

    private List<WaterfallSource> fetchAdSources() throws Exception {
        String url = API_BASE + "/v1/partner/device/" + DEVICE_ID + "/ads"
            + "?slot_w=1920&slot_h=1080"
            + "&orientation=landscape"
            + "&muted=0&autoplay=1"
            + "&ua=" + URLEncoder.encode(realUserAgent, "UTF-8")
            + "&lang=en-US"
            + "&screen_w=1920&screen_h=1080"
            + "&sdk_type=google-ima"
            + "&sdk_version=media3-1.5.1";

        Request req = new Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", realUserAgent)
            .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                Log.e(TAG, "/ads returned HTTP " + resp.code());
                return null;
            }
            String respBody = resp.body().string();
            JSONObject json = new JSONObject(respBody);
            if (!json.optBoolean("success", false)) {
                Log.e(TAG, "/ads returned success=false");
                return null;
            }

            JSONObject data = json.getJSONObject("data");
            JSONObject hbs = data.optJSONObject("header_bidding_settings");
            JSONArray sourcesArr;

            if (hbs != null) {
                // New response format: header_bidding_settings.vast_waterfall.sources
                JSONObject waterfall = hbs.getJSONObject("vast_waterfall");
                sourcesArr = waterfall.getJSONArray("sources");
            } else if (data.has("sources")) {
                // Fallback: direct sources array (earner-ads proxy format)
                sourcesArr = data.getJSONArray("sources");
            } else {
                Log.e(TAG, "/ads — no sources found in response");
                return null;
            }

            List<WaterfallSource> sources = new ArrayList<>();
            for (int i = 0; i < sourcesArr.length(); i++) {
                JSONObject s = sourcesArr.getJSONObject(i);
                if (!s.optBoolean("enabled", true)) continue;

                WaterfallSource ws = new WaterfallSource();
                ws.name = s.getString("name");
                ws.vastUrl = s.getString("vast_url");
                ws.requestId = s.optString("request_id", "req_" + System.currentTimeMillis() + "_" + ws.name);
                ws.timeoutMs = s.optInt("timeout_ms", 10000);
                ws.priority = s.optInt("priority", i + 1);
                sources.add(ws);
            }
            return sources;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WATERFALL WALK — try each source in order
    // ══════════════════════════════════════════════════════════════════════════

    private void walkWaterfall(List<WaterfallSource> sources, int index) {
        if (index >= sources.size()) {
            Log.w(TAG, "Waterfall exhausted — all " + sources.size() + " sources tried, no fill");
            WaterfallSource last = sources.get(sources.size() - 1);
            reportEvent("no_fill", last.requestId, last.name, null, null);
            adPlaying = false;
            mainHandler.post(() -> listener.onNoFill(last.name));
            return;
        }

        WaterfallSource source = sources.get(index);
        Log.i(TAG, "Trying source [" + (index + 1) + "/" + sources.size() + "]: " + source.name);

        new Thread(() -> {
            try {
                String vastXml = fetchVastXml(source);
                if (vastXml == null || vastXml.trim().isEmpty()
                    || vastXml.contains("<VAST version=\"3.0\"/>")
                    || vastXml.contains("<VAST version=\"2.0\"/>")) {
                    Log.w(TAG, source.name + " returned empty VAST — skipping");
                    reportEvent("error", source.requestId, source.name, "empty_vast", null);
                    walkWaterfall(sources, index + 1);
                    return;
                }

                Log.i(TAG, source.name + " returned VAST XML (" + vastXml.length() + " bytes)");

                // Play it on the UI thread
                final String xml = vastXml;
                mainHandler.post(() -> playVastXml(xml, source, sources, index));

            } catch (Exception e) {
                Log.e(TAG, source.name + " VAST fetch failed: " + e.getMessage());
                reportEvent("error", source.requestId, source.name, e.getMessage(), null);
                walkWaterfall(sources, index + 1);
            }
        }).start();
    }

    private String fetchVastXml(WaterfallSource source) throws IOException {
        // Replace cachebuster placeholders
        String url = source.vastUrl
            .replace("%%CACHEBUSTER%%", String.valueOf(System.currentTimeMillis()))
            .replace("[CACHEBUSTER]", String.valueOf(System.currentTimeMillis()));

        // Build request with custom timeout per source
        OkHttpClient sourceClient = httpClient.newBuilder()
            .readTimeout(source.timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(Math.min(source.timeoutMs, 8000), TimeUnit.MILLISECONDS)
            .build();

        Request req = new Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", realUserAgent)
            .build();

        try (Response resp = sourceClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                Log.e(TAG, source.name + " VAST → HTTP " + resp.code());
                return null;
            }
            // Return FULL UNMODIFIED XML — never strip wrappers or modify
            return resp.body().string();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IMA PLAYBACK — hand VAST XML to Media3 + ImaAdsLoader
    // ══════════════════════════════════════════════════════════════════════════

    private void playVastXml(String vastXml, WaterfallSource source,
                             List<WaterfallSource> allSources, int currentIndex) {
        currentRequestId = source.requestId;
        currentVariantName = source.name;
        currentImpressionId = UUID.randomUUID().toString();
        adStartTimeMs = 0;

        Log.i(TAG, "Playing VAST from " + source.name
            + " reqId=" + source.requestId
            + " impId=" + currentImpressionId);

        // Release previous player/loader
        releasePlayer();

        try {
            // Build IMA ads loader — NO setAdMediaMimeTypes()!
            // Per guide: "The mp4 MIME filter was the single biggest completion-rate killer"
            imaAdsLoader = new ImaAdsLoader.Builder(activity)
                .build();

            // Build ExoPlayer with IMA integration
            player = new ExoPlayer.Builder(activity)
                .setMediaSourceFactory(
                    new DefaultMediaSourceFactory(activity)
                        .setLocalAdInsertionComponents(
                            unusedAdTagUri -> imaAdsLoader, playerView))
                .build();

            playerView.setPlayer(player);
            imaAdsLoader.setPlayer(player);

            // Build data:text/xml URI for the VAST — Media3 internally calls setAdsResponse()
            Uri adsUri = Util.getDataUriForString("text/xml", vastXml);

            // Use a silent content URI (IMA needs a content item even for standalone ads)
            // We use a 1-second silent audio as the "content" — ads play as pre-roll
            Uri contentUri = Uri.parse("data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=");

            MediaItem mediaItem = new MediaItem.Builder()
                .setUri(contentUri)
                .setAdsConfiguration(
                    new MediaItem.AdsConfiguration.Builder(adsUri)
                        .build())
                .build();

            // Listen for playback events
            player.addListener(new Player.Listener() {
                private boolean adStartedReported = false;

                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_ENDED) {
                        Log.i(TAG, "Playback ended for " + source.name);
                        if (adStartedReported) {
                            int durationSec = (int) ((System.currentTimeMillis() - adStartTimeMs) / 1000);
                            reportEvent("ad_ended", currentRequestId, currentVariantName,
                                null, currentImpressionId);
                            adPlaying = false;
                            final int dur = Math.max(durationSec, 1);
                            mainHandler.post(() -> {
                                releasePlayer();
                                listener.onAdCompleted(source.name, dur, source.requestId);
                            });
                        } else {
                            // Playback ended without ad starting — IMA had no creative
                            Log.w(TAG, source.name + " — playback ended but no ad started");
                            mainHandler.post(() -> {
                                releasePlayer();
                                // Try next source
                                new Thread(() -> walkWaterfall(allSources, currentIndex + 1)).start();
                            });
                        }
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    Log.e(TAG, "Player error for " + source.name + ": " + error.getMessage()
                        + " code=" + error.errorCode);
                    reportEvent("error", currentRequestId, currentVariantName,
                        "exo_" + error.errorCode + "_" + error.getMessage(), null);
                    mainHandler.post(() -> {
                        releasePlayer();
                        // Try next source in waterfall
                        new Thread(() -> walkWaterfall(allSources, currentIndex + 1)).start();
                    });
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying && !adStartedReported && player != null && player.isPlayingAd()) {
                        adStartedReported = true;
                        adStartTimeMs = System.currentTimeMillis();
                        Log.i(TAG, "AD STARTED — " + source.name
                            + " reqId=" + source.requestId
                            + " impId=" + currentImpressionId);
                        reportEvent("ad_started", currentRequestId, currentVariantName,
                            null, currentImpressionId);
                        mainHandler.post(() -> listener.onAdStarted(source.name, source.requestId));
                    }
                }
            });

            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);

            // Safety timeout — if nothing plays in 15s, skip to next source
            mainHandler.postDelayed(() -> {
                if (adPlaying && adStartTimeMs == 0) {
                    Log.w(TAG, source.name + " — 15s timeout, no ad started");
                    releasePlayer();
                    new Thread(() -> walkWaterfall(allSources, currentIndex + 1)).start();
                }
            }, 15000);

        } catch (Exception e) {
            Log.e(TAG, "playVastXml failed for " + source.name + ": " + e.getMessage());
            reportEvent("error", source.requestId, source.name, e.getMessage(), null);
            releasePlayer();
            new Thread(() -> walkWaterfall(allSources, currentIndex + 1)).start();
        }
    }

    private void releasePlayer() {
        if (imaAdsLoader != null) {
            imaAdsLoader.setPlayer(null);
            imaAdsLoader.release();
            imaAdsLoader = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EVENT REPORTING — POST /v1/partner/device/{deviceId}/programmatic-event
    // ══════════════════════════════════════════════════════════════════════════

    private void reportEvent(String eventType, String requestId, String variantName,
                             String errorDetail, String impressionId) {
        // CRITICAL: request_id and variant_name are REQUIRED — events without them are silently dropped
        if (requestId == null || requestId.isEmpty() || variantName == null || variantName.isEmpty()) {
            Log.e(TAG, "reportEvent BLOCKED — missing required fields:"
                + " requestId=" + requestId + " variantName=" + variantName
                + " (Trillboards silently drops events without these)");
            return;
        }

        new Thread(() -> {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

                JSONObject body = new JSONObject();
                body.put("event", eventType);
                body.put("request_id", requestId);
                body.put("variant_name", variantName);
                if (impressionId != null) body.put("impression_id", impressionId);
                if (errorDetail != null) body.put("error_message", errorDetail);
                body.put("screen_id", SCREEN_ID);
                body.put("timestamp", sdf.format(new Date()));

                String url = API_BASE + "/v1/partner/device/" + DEVICE_ID + "/programmatic-event";
                Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                    .header("User-Agent", realUserAgent)
                    .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    Log.i(TAG, "Event " + eventType + " [" + variantName + "] → HTTP " + resp.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "reportEvent failed (" + eventType + "): " + e.getMessage());
            }
        }).start();

        // Also log to KV worker for dashboard tracking
        if ("ad_ended".equals(eventType) || "no_fill".equals(eventType)) {
            logToKV(eventType, variantName, eventType.equals("ad_ended"));
        }
    }

    /** Log impression to KV worker for the Curb dashboard. */
    private void logToKV(String type, String source, boolean completed) {
        new Thread(() -> {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

                JSONObject body = new JSONObject();
                body.put("type", type);
                body.put("screen_id", SCREEN_ID);
                body.put("device_id", DEVICE_ID);
                body.put("source", source);
                body.put("duration_ms", completed ? 30000 : 0);
                body.put("completed", completed);
                body.put("timestamp", sdf.format(new Date()));

                Request req = new Request.Builder()
                    .url(KV_URL)
                    .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                    .header("X-Proxy-Secret", KV_SECRET)
                    .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    Log.i(TAG, "KV " + type + " [" + source + "] → HTTP " + resp.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "KV log failed: " + e.getMessage());
            }
        }).start();
    }

    /** Cancel any playing ad and release resources. */
    public void cancel() {
        adPlaying = false;
        mainHandler.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    /** Full cleanup — stop heartbeat, release everything. */
    public void release() {
        stopHeartbeat();
        cancel();
        if (playerView != null && playerView.getParent() != null) {
            ((ViewGroup) playerView.getParent()).removeView(playerView);
        }
        Log.i(TAG, "NativeAdPlayer released");
    }
}
