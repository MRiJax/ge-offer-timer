/*
 * Copyright (c) 2026, SeismicRy
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package geoffertimer;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@PluginDescriptor(
        name = "GE Offer Timer",
        description = "Shows how long each Grand Exchange offer has been active",
        tags = {"grand", "exchange", "ge", "timer", "offers"}
)
public class GEOfferTimerPlugin extends Plugin
{
    private static final String CONFIG_GROUP = "geoffertimer";

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GETimerOverlay overlay;

    @Inject
    private ConfigManager configManager;

    @Inject
    private Client client;

    final Map<Integer, Instant> offerStartTimes = new HashMap<>();
    final Map<Integer, Long> completedOfferTimes = new HashMap<>();
    final Map<Integer, String> completedOfferStates = new HashMap<>();
    final Map<Integer, Integer> completedOfferItems = new HashMap<>();

    // Flag to ignore EMPTY events during login/logout transitions.
    // When logging in, the client fires EMPTY events for all GE slots
    // before firing the real BUYING/SELLING events. During logout, it
    // also fires EMPTY for all slots. We need to ignore both cases
    // to prevent saved timers from being wiped out.
    private boolean ignoreEmptyEvents = true;

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(overlay);
        loadSavedTimes();
        log.debug("GE Offer Timer started!");
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        saveTimes();
        offerStartTimes.clear();
        completedOfferTimes.clear();
        completedOfferStates.clear();
        completedOfferItems.clear();
        ignoreEmptyEvents = true;
        log.debug("GE Offer Timer stopped!");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGIN_SCREEN
                || event.getGameState() == GameState.HOPPING)
        {
            saveTimes();
            ignoreEmptyEvents = true;
        }
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            ignoreEmptyEvents = true;
            loadSavedTimes();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        // After the first game tick, the initial GE offer events have
        // finished firing, so it's safe to process EMPTY events again
        if (ignoreEmptyEvents)
        {
            ignoreEmptyEvents = false;
            log.debug("Initial login phase complete, now processing EMPTY events");
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        log.debug("GE offer changed - slot: {} state: {}", event.getSlot(), event.getOffer().getState());

        int slot = event.getSlot();
        GrandExchangeOffer offer = event.getOffer();
        GrandExchangeOfferState state = offer.getState();

        // New active offer - start timer
        if (state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING)
        {
            if (!offerStartTimes.containsKey(slot))
            {
                offerStartTimes.put(slot, Instant.now());
                completedOfferTimes.remove(slot);
                completedOfferStates.remove(slot);
                completedOfferItems.remove(slot);
                saveTimes();
            }
        }

        // Offer completed - freeze timer
        if (state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD)
        {
            Instant startTime = offerStartTimes.get(slot);
            if (startTime != null)
            {
                long elapsed = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                completedOfferTimes.put(slot, elapsed);
                completedOfferStates.put(slot, state == GrandExchangeOfferState.BOUGHT ? "BOUGHT" : "SOLD");
                completedOfferItems.put(slot, offer.getItemId());
                offerStartTimes.remove(slot);
                saveTimes();
            }
        }

        // Cancelled - freeze timer with cancelled label
        if (state == GrandExchangeOfferState.CANCELLED_BUY
                || state == GrandExchangeOfferState.CANCELLED_SELL)
        {
            Instant startTime = offerStartTimes.get(slot);
            if (startTime != null)
            {
                long elapsed = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                completedOfferTimes.put(slot, elapsed);
                completedOfferStates.put(slot, "CANCELLED");
                completedOfferItems.put(slot, offer.getItemId());
                offerStartTimes.remove(slot);
                saveTimes();
            }
        }

        // Empty slot - remove everything (but only when not during login/logout)
        if (state == GrandExchangeOfferState.EMPTY)
        {
            if (!ignoreEmptyEvents)
            {
                offerStartTimes.remove(slot);
                completedOfferTimes.remove(slot);
                completedOfferStates.remove(slot);
                completedOfferItems.remove(slot);
                saveTimes();
            }
        }
    }

    private void saveTimes()
    {
        for (int i = 0; i < 8; i++)
        {
            // Save active offer start times
            if (offerStartTimes.containsKey(i))
            {
                configManager.setRSProfileConfiguration(CONFIG_GROUP, "slot" + i, offerStartTimes.get(i).toEpochMilli());
            }
            else
            {
                configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "slot" + i);
            }

            // Save completed/cancelled offer data
            if (completedOfferTimes.containsKey(i))
            {
                configManager.setRSProfileConfiguration(CONFIG_GROUP, "completed_time" + i, completedOfferTimes.get(i));
                configManager.setRSProfileConfiguration(CONFIG_GROUP, "completed_state" + i, completedOfferStates.get(i));
                configManager.setRSProfileConfiguration(CONFIG_GROUP, "completed_item" + i, completedOfferItems.get(i));
            }
            else
            {
                configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "completed_time" + i);
                configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "completed_state" + i);
                configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "completed_item" + i);
            }
        }
    }

    private void loadSavedTimes()
    {
        for (int i = 0; i < 8; i++)
        {
            // Load active offer start times
            String saved = configManager.getRSProfileConfiguration(CONFIG_GROUP, "slot" + i);
            if (saved != null)
            {
                try
                {
                    long epochMilli = Long.parseLong(saved);
                    offerStartTimes.put(i, Instant.ofEpochMilli(epochMilli));
                }
                catch (NumberFormatException e)
                {
                    log.debug("Failed to parse saved time for slot {}", i);
                }
            }

            // Load completed/cancelled offer data
            String completedTime = configManager.getRSProfileConfiguration(CONFIG_GROUP, "completed_time" + i);
            String completedState = configManager.getRSProfileConfiguration(CONFIG_GROUP, "completed_state" + i);
            String completedItem = configManager.getRSProfileConfiguration(CONFIG_GROUP, "completed_item" + i);
            if (completedTime != null && completedState != null)
            {
                try
                {
                    completedOfferTimes.put(i, Long.parseLong(completedTime));
                    completedOfferStates.put(i, completedState);
                    if (completedItem != null)
                    {
                        completedOfferItems.put(i, Integer.parseInt(completedItem));
                    }
                }
                catch (NumberFormatException e)
                {
                    log.debug("Failed to parse completed offer data for slot {}", i);
                }
            }
        }
    }

    @Provides
    GEOfferTimerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GEOfferTimerConfig.class);
    }
}
