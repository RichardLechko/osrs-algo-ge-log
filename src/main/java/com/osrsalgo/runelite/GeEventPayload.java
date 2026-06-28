package com.osrsalgo.runelite;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

/** Wire shape for POST /api/ge-events. Field names use SerializedName
 *  to keep snake_case on the wire (matches the Flask convention) while
 *  keeping camelCase here. */
@Value
@Builder
public class GeEventPayload
{
    @SerializedName("plugin_lifecycle_id") String pluginLifecycleId;
    @SerializedName("slot_index")          int slotIndex;
    @SerializedName("item_id")             int itemId;
    @SerializedName("item_name")           String itemName;
                                           String side;            // "buy" | "sell"
                                           String status;          // pending/partial/filled/cancelled
    @SerializedName("total_quantity")      int totalQuantity;
    @SerializedName("filled_quantity")     int filledQuantity;
                                           int price;
    @SerializedName("avg_fill_price")      Double avgFillPrice;    // nullable
                                           long spent;
    @SerializedName("event_ts")            String eventTs;         // ISO-8601 UTC
}
