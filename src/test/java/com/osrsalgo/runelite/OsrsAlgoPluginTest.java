package com.osrsalgo.runelite;

import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.*;

public class OsrsAlgoPluginTest
{
    @Test public void buyingZeroFilledIsPending() {
        assertEquals("pending", OsrsAlgoPlugin.mapStatus(GrandExchangeOfferState.BUYING, 100, 0));
    }
    @Test public void buyingPartialIsPartial() {
        assertEquals("partial", OsrsAlgoPlugin.mapStatus(GrandExchangeOfferState.BUYING, 100, 50));
    }
    @Test public void boughtIsFilled() {
        assertEquals("filled", OsrsAlgoPlugin.mapStatus(GrandExchangeOfferState.BOUGHT, 100, 100));
    }
    @Test public void cancelledBuyIsCancelled() {
        assertEquals("cancelled", OsrsAlgoPlugin.mapStatus(GrandExchangeOfferState.CANCELLED_BUY, 100, 30));
    }
    @Test public void sideFromBoughtIsBuy() {
        assertEquals("buy", OsrsAlgoPlugin.mapSide(GrandExchangeOfferState.BOUGHT));
    }
    @Test public void sideFromSoldIsSell() {
        assertEquals("sell", OsrsAlgoPlugin.mapSide(GrandExchangeOfferState.SOLD));
    }
    @Test public void sideFromEmptyIsNull() {
        assertNull(OsrsAlgoPlugin.mapSide(GrandExchangeOfferState.EMPTY));
    }
}
