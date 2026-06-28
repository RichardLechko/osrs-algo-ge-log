package com.osrsalgo.runelite;

import lombok.Value;

/** Per-slot in-memory state held by SlotTracker. Immutable — updates
 *  produce a fresh instance. */
@Value
public class SlotState
{
    String pluginLifecycleId;   // UUID assigned by the plugin
    Long backendOrderId;        // null until the first POST returns success
    int lastQuantitySold;       // last observed quantitySold (informational; backend handles idempotency)
    String lastStatus;          // last observed status (informational)
}
