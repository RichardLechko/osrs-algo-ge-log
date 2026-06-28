package com.osrsalgo.runelite;

import com.google.inject.Provides;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
    name = "OSRS Algo (GE log)",
    description = "Auto-logs Grand Exchange activity to the OSRS Algo backend",
    tags = {"ge", "grand exchange", "flipping", "algo"}
)
public class OsrsAlgoPlugin extends Plugin
{
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_QUEUE_LINES = 10_000;
    private static final long DRAIN_INTERVAL_SEC = 30;

    @Inject private OsrsAlgoConfig config;
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ItemManager itemManager;
    @Inject private OkHttpClient httpClient;
    @Inject private Gson gson;
    @Inject private ClientToolbar clientToolbar;
    @Inject private EventBus eventBus;

    private final SlotTracker tracker = new SlotTracker();
    private BackendClient backend;
    private ScheduledExecutorService scheduler;
    private OsrsAlgoPanel panel;
    private NavigationButton navButton;
    private EventBus.Subscriber geSubscriber;

    @Override
    protected void startUp() throws Exception
    {
        Path pluginDir = RuneLite.RUNELITE_DIR.toPath().resolve("osrs-algo");
        Files.createDirectories(pluginDir);
        Path queueFile = pluginDir.resolve("queue.jsonl");
        backend = new BackendClient(httpClient, gson, config.backendUrl(),
            queueFile, MAX_ATTEMPTS, MAX_QUEUE_LINES);
        panel = new OsrsAlgoPanel();
        panel.setStatus("ready", Color.GREEN);
        navButton = NavigationButton.builder()
            .tooltip("OSRS Algo")
            .icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);
        // Manual EventBus registration because @Subscribe relies on a
        // LambdaMetafactory teleport into the plugin's classloader, which
        // fails for sideloaded plugins (PluginClassLoader doesn't
        // implement PrivateLookupableClassLoader). Registering a method
        // reference here creates the lambda in OUR classloader, sidestepping
        // the teleport. See Runelite ReflectUtil.installLookupHelper Javadoc.
        geSubscriber = eventBus.register(GrandExchangeOfferChanged.class,
            this::onGrandExchangeOfferChanged, 0f);
        scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "osrs-algo-drain"));
        scheduler.scheduleWithFixedDelay(this::drainOnce,
            DRAIN_INTERVAL_SEC, DRAIN_INTERVAL_SEC, TimeUnit.SECONDS);
        clientThread.invokeLater(this::reconcileOnStartup);
        log.info("OSRS Algo plugin started — backend={}", config.backendUrl());
    }

    @Override
    protected void shutDown()
    {
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        // Unregister our manual subscription via the Subscriber token returned
        // at registration. The manual register() path stores the Consumer as
        // the Subscriber's "object", so unregister(Object) with `this` would
        // not match — we must use the Subscriber overload instead.
        eventBus.unregister(geSubscriber);
        if (scheduler != null) scheduler.shutdownNow();
        log.info("OSRS Algo plugin stopped");
    }

    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged ev)
    {
        if (!config.enabled()) return;
        int slot = ev.getSlot();
        GrandExchangeOffer offer = ev.getOffer();
        if (offer.getState() == GrandExchangeOfferState.EMPTY) {
            tracker.onEmpty(slot);
            return;
        }
        sendForSlot(slot, offer);
    }

    private void sendForSlot(int slot, GrandExchangeOffer offer)
    {
        String status = mapStatus(offer.getState(), offer.getTotalQuantity(),
            offer.getQuantitySold());
        String side = mapSide(offer.getState());
        if (side == null) return;
        SlotState state = tracker.onActivity(slot, status, offer.getQuantitySold());
        ItemComposition def = itemManager.getItemComposition(offer.getItemId());
        Double avg = offer.getQuantitySold() > 0
            ? offer.getSpent() / (double) offer.getQuantitySold() : null;
        GeEventPayload payload = GeEventPayload.builder()
            .pluginLifecycleId(state.getPluginLifecycleId())
            .slotIndex(slot)
            .itemId(offer.getItemId())
            .itemName(def != null ? def.getName() : "")
            .side(side).status(status)
            .totalQuantity(offer.getTotalQuantity())
            .filledQuantity(offer.getQuantitySold())
            .price(offer.getPrice())
            .avgFillPrice(avg)
            .spent(offer.getSpent())
            .eventTs(Instant.now().toString())
            .build();
        Long orderId = backend.postEvent(payload);
        if (orderId != null) tracker.rememberOrderId(slot, orderId);
        if (panel != null) {
            panel.setLastEvent("Last event: " + side + " "
                + (def != null ? def.getName() : "#" + offer.getItemId())
                + " (" + status + ")");
            panel.setStatus(orderId != null ? "ok" : "queued",
                orderId != null ? Color.GREEN : Color.ORANGE);
        }
    }

    private void reconcileOnStartup()
    {
        if (!config.enabled()) return;
        java.util.List<ReconcileRequest.Slot> slots = new java.util.ArrayList<>();
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        for (int i = 0; i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) continue;
            String side = mapSide(offer.getState());
            if (side == null) continue;
            slots.add(new ReconcileRequest.Slot(i, offer.getItemId(), side,
                offer.getTotalQuantity(), offer.getPrice(), offer.getQuantitySold()));
        }
        if (slots.isEmpty()) return;
        java.util.Map<Integer, BackendClient.ReconciledSlot> matches =
            backend.reconcile(new ReconcileRequest(slots));
        for (java.util.Map.Entry<Integer, BackendClient.ReconciledSlot> e : matches.entrySet()) {
            tracker.rehydrate(e.getKey(), e.getValue().getPluginLifecycleId(),
                e.getValue().getOrderId());
        }
        // For every active slot (matched or not), fire a synthetic update so
        // the backend learns the CURRENT state — if it's matched, this updates
        // the existing order; if not, it creates a new one with the fresh UUID.
        for (int i = 0; i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) continue;
            sendForSlot(i, offer);
        }
    }

    private void drainOnce()
    {
        try {
            int drained = backend.drainQueue();
            if (panel != null && drained > 0) {
                panel.setStatus("ok", Color.GREEN);
            }
        } catch (Exception e) {
            log.warn("scheduled drain failed: {}", e.getMessage());
            if (panel != null) panel.setStatus("backend down", Color.RED);
        }
    }

    @Provides
    OsrsAlgoConfig provideConfig(ConfigManager cm) { return cm.getConfig(OsrsAlgoConfig.class); }

    // ---- pure helpers (tested in OsrsAlgoPluginTest) ----

    /** Pure function: map Runelite's OfferState + offer numbers into our
     *  internal status string. Extracted so it's trivially unit-testable
     *  without instantiating a Client. */
    static String mapStatus(GrandExchangeOfferState state, int totalQty, int sold)
    {
        switch (state) {
            case BUYING:
            case SELLING:
                return sold > 0 && sold < totalQty ? "partial"
                     : sold == 0 ? "pending"
                     : "filled";   // odd case — fully sold but state hasn't flipped yet
            case BOUGHT:
            case SOLD:
                return "filled";
            case CANCELLED_BUY:
            case CANCELLED_SELL:
                return "cancelled";
            case EMPTY:
            default:
                return "empty";
        }
    }

    /** Pure: derive 'buy' vs 'sell' from the state. EMPTY returns null
     *  (caller should not be posting for empty slots). */
    static String mapSide(GrandExchangeOfferState state)
    {
        switch (state) {
            case BUYING: case BOUGHT: case CANCELLED_BUY: return "buy";
            case SELLING: case SOLD: case CANCELLED_SELL: return "sell";
            default: return null;
        }
    }
}
