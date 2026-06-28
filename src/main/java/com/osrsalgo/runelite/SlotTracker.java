package com.osrsalgo.runelite;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks per-slot lifecycle state in memory. One instance per plugin
 *  lifetime; cleared on plugin shutdown.
 *
 *  Invariants:
 *  - A slot's UUID is generated lazily the first time we see activity
 *    on it after EMPTY (or after the tracker is created).
 *  - The UUID survives across partial / filled / cancelled states; it
 *    is only discarded when the slot goes EMPTY (i.e. user collected
 *    the items / GP and a new offer can now occupy the slot).
 *  - rehydrate() seeds a slot from the reconcile response, so the
 *    first event after plugin restart can update the right backend
 *    order instead of creating a duplicate. */
public class SlotTracker
{
    private final Map<Integer, SlotState> bySlot = new HashMap<>();

    public synchronized SlotState onActivity(int slotIndex, String status, int quantitySold)
    {
        SlotState existing = bySlot.get(slotIndex);
        String uuid = existing != null
            ? existing.getPluginLifecycleId()
            : UUID.randomUUID().toString();
        Long orderId = existing != null ? existing.getBackendOrderId() : null;
        SlotState next = new SlotState(uuid, orderId, quantitySold, status);
        bySlot.put(slotIndex, next);
        return next;
    }

    public synchronized void onEmpty(int slotIndex)
    {
        bySlot.remove(slotIndex);
    }

    public synchronized SlotState get(int slotIndex)
    {
        return bySlot.get(slotIndex);
    }

    public synchronized void rehydrate(int slotIndex, String lifecycleId, long orderId)
    {
        bySlot.put(slotIndex, new SlotState(lifecycleId, orderId, 0, "pending"));
    }

    public synchronized void rememberOrderId(int slotIndex, long orderId)
    {
        SlotState s = bySlot.get(slotIndex);
        if (s == null) return;
        bySlot.put(slotIndex, new SlotState(
            s.getPluginLifecycleId(), orderId,
            s.getLastQuantitySold(), s.getLastStatus()
        ));
    }
}
