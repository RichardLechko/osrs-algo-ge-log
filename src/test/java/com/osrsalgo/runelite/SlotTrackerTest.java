package com.osrsalgo.runelite;

import org.junit.Test;
import static org.junit.Assert.*;

/** SlotTracker is pure — no network, no client API. Tests pin the
 *  UUID lifecycle invariants (created on EMPTY → active, retained
 *  during fills, dropped on EMPTY). */
public class SlotTrackerTest
{
    @Test
    public void newSlotGetsFreshUuid() {
        SlotTracker t = new SlotTracker();
        SlotState s = t.onActivity(3, "pending", 0);
        assertNotNull(s.getPluginLifecycleId());
        assertNull(s.getBackendOrderId());
        assertEquals(0, s.getLastQuantitySold());
        assertEquals("pending", s.getLastStatus());
    }

    @Test
    public void subsequentActivityKeepsSameUuid() {
        SlotTracker t = new SlotTracker();
        String firstUuid = t.onActivity(3, "pending", 0).getPluginLifecycleId();
        String secondUuid = t.onActivity(3, "partial", 5000).getPluginLifecycleId();
        assertEquals(firstUuid, secondUuid);
    }

    @Test
    public void emptyDropsTheSlot() {
        SlotTracker t = new SlotTracker();
        String firstUuid = t.onActivity(3, "pending", 0).getPluginLifecycleId();
        t.onEmpty(3);
        // Next activity on the same slot → fresh UUID, fresh state.
        String thirdUuid = t.onActivity(3, "pending", 0).getPluginLifecycleId();
        assertNotEquals(firstUuid, thirdUuid);
        assertEquals(thirdUuid, t.get(3).getPluginLifecycleId());
    }

    @Test
    public void differentSlotsGetDifferentUuids() {
        SlotTracker t = new SlotTracker();
        String a = t.onActivity(0, "pending", 0).getPluginLifecycleId();
        String b = t.onActivity(5, "pending", 0).getPluginLifecycleId();
        assertNotEquals(a, b);
    }

    @Test
    public void rehydrateInstallsUuidForExistingSlot() {
        SlotTracker t = new SlotTracker();
        t.rehydrate(3, "uuid-from-reconcile", 42L);
        SlotState s = t.get(3);
        assertEquals("uuid-from-reconcile", s.getPluginLifecycleId());
        assertEquals(Long.valueOf(42L), s.getBackendOrderId());
    }

    @Test
    public void rememberBackendOrderIdUpdatesExistingSlot() {
        SlotTracker t = new SlotTracker();
        t.onActivity(3, "pending", 0);
        t.rememberOrderId(3, 99L);
        assertEquals(Long.valueOf(99L), t.get(3).getBackendOrderId());
    }
}
