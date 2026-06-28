package com.osrsalgo.runelite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** BackendClient owns HTTP I/O + the offline queue. Tests use OkHttp's
 *  MockWebServer so no real network and no real backend needed. */
public class BackendClientTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private MockWebServer server;
    private BackendClient client;
    private Path queueFile;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        queueFile = tmp.newFile("queue.jsonl").toPath();
        client = new BackendClient(
            new OkHttpClient(), new com.google.gson.Gson(),
            server.url("/").toString(),
            queueFile, 10);
    }

    @After public void tearDown() throws Exception { server.shutdown(); }

    private GeEventPayload samplePayload() {
        return GeEventPayload.builder()
            .pluginLifecycleId("uuid-1")
            .slotIndex(0).itemId(11212).itemName("Dragonstone bolts (e)")
            .side("buy").status("pending")
            .totalQuantity(11000).filledQuantity(0).price(379)
            .avgFillPrice(null).spent(0)
            .eventTs("2026-06-27T15:00:00Z")
            .build();
    }

    @Test
    public void successfulPostReturnsOrderId() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"order_id\":42,\"created\":true}"));
        Long orderId = client.postEvent(samplePayload());
        assertEquals(Long.valueOf(42L), orderId);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void singleAttemptFailureReturnsNullAndQueues() throws Exception {
        // One failed call → null returned, payload appended to queue.
        server.enqueue(new MockResponse().setSocketPolicy(
            okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        Long orderId = client.postEvent(samplePayload());
        assertNull(orderId);
        assertEquals(1, server.getRequestCount());   // exactly one attempt
        java.util.List<String> lines = Files.readAllLines(queueFile);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"plugin_lifecycle_id\":\"uuid-1\""));
    }

    @Test
    public void drainQueueRePostsAndClearsOnSuccess() throws Exception {
        // Seed: a single queued event from a prior session.
        String seeded = new com.google.gson.Gson().toJson(samplePayload());
        Files.write(queueFile, java.util.Collections.singletonList(seeded));
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"order_id\":99}"));
        int drained = client.drainQueue();
        assertEquals(1, drained);
        assertEquals(0, Files.readAllLines(queueFile).size());
    }

    @Test
    public void drainFailureLeavesEventOnQueue() throws Exception {
        String seeded = new com.google.gson.Gson().toJson(samplePayload());
        Files.write(queueFile, java.util.Collections.singletonList(seeded));
        server.enqueue(new MockResponse().setSocketPolicy(
            okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        int drained = client.drainQueue();
        assertEquals(0, drained);
        // Event still on disk.
        assertEquals(1, Files.readAllLines(queueFile).size());
    }

    @Test
    public void queueRespectsHardCapByDroppingOldest() throws Exception {
        // Cap is 10 in the @Before setup.
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lines.add("{\"plugin_lifecycle_id\":\"old-" + i + "\"}");
        }
        Files.write(queueFile, lines);
        // Force one more append via a failing post.
        server.enqueue(new MockResponse().setSocketPolicy(
            okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        client.postEvent(samplePayload());
        java.util.List<String> after = Files.readAllLines(queueFile);
        assertEquals(10, after.size());
        assertFalse(after.get(0).contains("old-0"));   // oldest dropped
        assertTrue(after.get(9).contains("uuid-1"));   // new one is last
    }

    @Test
    public void reconcileParsesMatchesMap() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
            "{\"matches\":{\"3\":{\"order_id\":42,\"plugin_lifecycle_id\":\"uuid-X\"}}}"));
        ReconcileRequest req = new ReconcileRequest(java.util.Collections.singletonList(
            new ReconcileRequest.Slot(3, 11212, "buy", 11000, 379, 0)));
        java.util.Map<Integer, BackendClient.ReconciledSlot> matches = client.reconcile(req);
        assertEquals(1, matches.size());
        assertEquals(Long.valueOf(42L), matches.get(3).getOrderId());
        assertEquals("uuid-X", matches.get(3).getPluginLifecycleId());
    }
}
