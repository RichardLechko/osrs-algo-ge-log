package com.osrsalgo.runelite;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.Value;

/** Wire shape for POST /api/ge-events/reconcile. */
@Value
public class ReconcileRequest
{
    List<Slot> slots;

    @Value
    public static class Slot
    {
        @SerializedName("slot_index")     int slotIndex;
        @SerializedName("item_id")        int itemId;
                                          String side;
        @SerializedName("total_quantity") int totalQuantity;
                                          int price;
        @SerializedName("filled_quantity") int filledQuantity;
    }
}
