package com.osrsalgo.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/** User-tunable settings exposed in Runelite's config dialog. */
@ConfigGroup("osrsAlgo")
public interface OsrsAlgoConfig extends Config
{
    @ConfigItem(
        keyName = "backendUrl",
        name = "Backend URL",
        description = "Base URL of the Flask backend that ingests GE events"
    )
    default String backendUrl() { return "http://127.0.0.1:5000"; }

    @ConfigItem(
        keyName = "enabled",
        name = "Send events",
        description = "When off, GE events are observed but NOT posted (debug mode)"
    )
    default boolean enabled() { return true; }
}
