package com.osrsalgo.runelite;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

/** Thin HTTP wrapper for the Flask backend. Owns the offline queue.
 *  File I/O is serialized via fileLock; HTTP work runs without any
 *  lock so the client thread isn't blocked during a drain. */
@Slf4j
public class BackendClient
{
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final Gson gson;
    private final String baseUrl;
    private final Path queueFile;
    private final int maxQueueLines;
    private final Object fileLock = new Object();

    public BackendClient(OkHttpClient http, Gson gson, String baseUrl, Path queueFile,
                         int maxQueueLines)
    {
        this.http = http;
        this.gson = gson;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.queueFile = queueFile;
        this.maxQueueLines = maxQueueLines;
    }

    /** Returns the backend order_id on success, null on failure (event
     *  is queued for later drain). One HTTP attempt only — retries
     *  happen via the scheduled drain timer in OsrsAlgoPlugin, which
     *  uses a ScheduledExecutorService (Plugin Hub policy: no
     *  Thread.sleep, no Thread.interrupt). */
    public Long postEvent(GeEventPayload payload)
    {
        Long orderId = postEventInternal(payload);
        if (orderId == null) {
            // Single attempt failed — queue for the next scheduled drain.
            appendToQueue(payload);
        }
        return orderId;
    }

    /** Returns the count of events successfully re-posted. File I/O is
     *  serialized via fileLock; HTTP work runs without any lock so a
     *  concurrent postEvent() from the client thread isn't blocked.
     *  Events appended during drain are preserved by re-reading the
     *  queue before the rewrite. */
    public int drainQueue()
    {
        java.util.List<String> queued;
        synchronized (fileLock) {
            try {
                queued = java.nio.file.Files.exists(queueFile)
                    ? new java.util.ArrayList<>(java.nio.file.Files.readAllLines(queueFile))
                    : java.util.Collections.emptyList();
            } catch (IOException e) {
                log.warn("queue read failed: {}", e.getMessage());
                return 0;
            }
        }
        if (queued.isEmpty()) return 0;

        java.util.Set<String> succeededLines = new java.util.HashSet<>();
        int succeeded = 0;
        for (String line : queued) {
            GeEventPayload payload;
            try {
                payload = gson.fromJson(line, GeEventPayload.class);
            } catch (Exception e) {
                log.warn("skipping malformed queued event: {}", e.getMessage());
                succeededLines.add(line);   // remove unparseable junk too
                continue;
            }
            Long orderId = postEventInternal(payload);
            if (orderId != null) {
                succeeded++;
                succeededLines.add(line);
            }
        }

        // Rewrite the file: re-read (so any concurrent appendToQueue is
        // preserved), drop the lines we successfully processed, write back.
        synchronized (fileLock) {
            try {
                java.util.List<String> current = java.nio.file.Files.exists(queueFile)
                    ? new java.util.ArrayList<>(java.nio.file.Files.readAllLines(queueFile))
                    : new java.util.ArrayList<>();
                current.removeAll(succeededLines);
                java.nio.file.Files.write(queueFile, current);
            } catch (IOException e) {
                log.warn("queue rewrite failed: {}", e.getMessage());
            }
        }
        return succeeded;
    }

    /** Single-attempt POST. Returns order_id on 2xx, null on any
     *  failure (IO, non-2xx, parse error). Used by both postEvent
     *  (which queues on null) and drainQueue (which leaves the line
     *  in the queue on null). */
    private Long postEventInternal(GeEventPayload payload)
    {
        String json = gson.toJson(payload);
        Request req = new Request.Builder()
            .url(baseUrl + "/api/ge-events")
            .post(RequestBody.create(JSON, json))
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "{}";
                return new JsonParser().parse(body).getAsJsonObject()
                    .get("order_id").getAsLong();
            }
            log.warn("ge-events POST got HTTP {}", resp.code());
        } catch (IOException e) {
            log.warn("ge-events POST IO error: {}", e.getMessage());
        }
        return null;
    }

    private void appendToQueue(GeEventPayload payload)
    {
        synchronized (fileLock) {
            try {
                java.util.List<String> current = java.nio.file.Files.exists(queueFile)
                    ? new java.util.ArrayList<>(java.nio.file.Files.readAllLines(queueFile))
                    : new java.util.ArrayList<>();
                current.add(gson.toJson(payload));
                // Cap at maxQueueLines — drop from the head if we exceed it.
                while (current.size() > maxQueueLines) {
                    current.remove(0);
                    log.warn("queue cap reached — oldest event dropped");
                }
                java.nio.file.Files.write(queueFile, current);
            } catch (IOException e) {
                log.error("queue append failed: {}", e.getMessage());
            }
        }
    }

    @lombok.Value
    public static class ReconciledSlot
    {
        Long orderId;
        String pluginLifecycleId;
    }

    /** Called once on plugin startup. Posts the current state of every
     *  non-EMPTY GE slot; backend returns which ones map to existing
     *  open plugin orders. Failures return empty (caller proceeds as
     *  if no slots matched — fresh UUIDs on next event). */
    public java.util.Map<Integer, ReconciledSlot> reconcile(ReconcileRequest body)
    {
        String json = gson.toJson(body);
        Request req = new Request.Builder()
            .url(baseUrl + "/api/ge-events/reconcile")
            .post(RequestBody.create(JSON, json))
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                log.warn("reconcile got HTTP {}", resp.code());
                return java.util.Collections.emptyMap();
            }
            String text = resp.body() != null ? resp.body().string() : "{}";
            JsonObject parsed = new JsonParser().parse(text).getAsJsonObject();
            JsonObject matches = parsed.has("matches") && parsed.get("matches").isJsonObject()
                ? parsed.getAsJsonObject("matches") : new JsonObject();
            java.util.Map<Integer, ReconciledSlot> out = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : matches.entrySet()) {
                JsonObject m = e.getValue().getAsJsonObject();
                out.put(Integer.parseInt(e.getKey()),
                    new ReconciledSlot(m.get("order_id").getAsLong(),
                                       m.get("plugin_lifecycle_id").getAsString()));
            }
            return out;
        } catch (IOException e) {
            log.warn("reconcile network error: {}", e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }
}
