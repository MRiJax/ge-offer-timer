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
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
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

    // On login (and after a world hop) the client streams the state of all GE
    // slots over the first couple of game ticks. The canonical Grand Exchange
    // plugin uses the same 2-tick window. Until that burst settles, the live
    // offers still read EMPTY, so we must not act on EMPTY events or we'd wipe
    // timers that are about to be restored.
    private static final int LOGIN_BURST_TICKS = 2;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GETimerOverlay overlay;

    @Inject
    private ConfigManager configManager;

    @Inject
    private Client client;

    // Active offers: when the offer started, plus the item id so we can tell a
    // continuing offer (a partial fill re-fires BUYING/SELLING) apart from the
    // slot being reused by a brand-new offer.
    final Map<Integer, Instant> offerStartTimes = new HashMap<>();
    final Map<Integer, Integer> offerItems = new HashMap<>();

    // Completed / cancelled offers, frozen at the moment they finished.
    final Map<Integer, Long> completedOfferTimes = new HashMap<>();
    final Map<Integer, String> completedOfferStates = new HashMap<>();
    final Map<Integer, Integer> completedOfferItems = new HashMap<>();

    // True while we're inside the login/hop burst window described above.
    private boolean ignoreEmptyEvents = true;
    private int loginBurstTicks = 0;

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(overlay);
        ignoreEmptyEvents = true;
        loginBurstTicks = 0;
        loadSavedTimes();
        // Picks up any offers that were already live when the plugin was
        // enabled mid-session (no GE events fire in that case).
        seedActiveOffersFromClient();
        log.debug("GE Offer Timer started!");
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        saveTimes();
        offerStartTimes.clear();
        offerItems.clear();
        completedOfferTimes.clear();
        completedOfferStates.clear();
        completedOfferItems.clear();
        ignoreEmptyEvents = true;
        loginBurstTicks = 0;
        log.debug("GE Offer Timer stopped!");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Re-arm the burst window: the GE state is about to stream in.
            ignoreEmptyEvents = true;
            loginBurstTicks = 0;
            loadSavedTimes();
        }
        else
        {
            // Login screen / logging in / hopping / loading / connection lost.
            // Persist before anything can disturb the maps, and suppress the
            // EMPTY burst the client fires for every slot during the transition.
            saveTimes();
            ignoreEmptyEvents = true;
            loginBurstTicks = 0;
        }
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
    {
        // Fired once the account's config profile is actually resolved. Loading
        // here (not just at LOGGED_IN) guarantees getRSProfileConfiguration can
        // see the saved values, which is what lets timers survive a full client
        // restart rather than coming back blank.
        loadSavedTimes();
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (ignoreEmptyEvents && ++loginBurstTicks >= LOGIN_BURST_TICKS)
        {
            ignoreEmptyEvents = false;
            log.debug("Login burst window closed, now processing EMPTY events");
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        int slot = event.getSlot();
        GrandExchangeOffer offer = event.getOffer();
        GrandExchangeOfferState state = offer.getState();
        log.debug("GE offer changed - slot: {} state: {}", slot, state);

        switch (state)
        {
            case BUYING:
            case SELLING:
                handleActiveOffer(slot, offer);
                break;
            case BOUGHT:
            case SOLD:
                handleCompletedOffer(slot, offer, state == GrandExchangeOfferState.BOUGHT ? "BOUGHT" : "SOLD");
                break;
            case CANCELLED_BUY:
            case CANCELLED_SELL:
                handleCompletedOffer(slot, offer, "CANCELLED");
                break;
            case EMPTY:
                handleEmptyOffer(slot);
                break;
        }
    }

    private void handleActiveOffer(int slot, GrandExchangeOffer offer)
    {
        int itemId = offer.getItemId();
        Integer trackedItem = offerItems.get(slot);

        // A "new" offer is one we aren't tracking yet, or one where the slot now
        // holds a different item than we last saw (the slot was reused).
        boolean newOffer = !offerStartTimes.containsKey(slot)
                || (trackedItem != null && trackedItem != itemId);

        if (newOffer)
        {
            offerStartTimes.put(slot, Instant.now());
            offerItems.put(slot, itemId);
            completedOfferTimes.remove(slot);
            completedOfferStates.remove(slot);
            completedOfferItems.remove(slot);

            // Don't persist offers that show up during the login burst: the
            // "start = now" above is only a placeholder until loadSavedTimes()
            // restores the real start time, and saving it would clobber the
            // genuine value still sitting in the config.
            if (!ignoreEmptyEvents)
            {
                saveTimes();
            }
        }
        else if (trackedItem == null)
        {
            // Continuing offer restored from older saved data that had no item
            // id; record it so a future slot reuse can be detected.
            offerItems.put(slot, itemId);
            if (!ignoreEmptyEvents)
            {
                saveTimes();
            }
        }
        // Otherwise it's the same ongoing offer (a partial fill) — keep the
        // original start time untouched.
    }

    private void handleCompletedOffer(int slot, GrandExchangeOffer offer, String label)
    {
        Instant startTime = offerStartTimes.get(slot);

        // Nothing to freeze if we never saw the offer start, or if we've already
        // recorded this completion (the client can fire duplicate completed /
        // cancelled events for a single offer).
        if (startTime == null || completedOfferTimes.containsKey(slot))
        {
            return;
        }

        long elapsed = Instant.now().toEpochMilli() - startTime.toEpochMilli();
        completedOfferTimes.put(slot, elapsed);
        completedOfferStates.put(slot, label);
        completedOfferItems.put(slot, offer.getItemId());
        offerStartTimes.remove(slot);
        offerItems.remove(slot);
        saveTimes();
    }

    private void handleEmptyOffer(int slot)
    {
        // During the login/hop burst an EMPTY just means "not synced yet", so
        // ignore it. Genuine empties (the offer was collected) are handled once
        // the window has closed.
        if (ignoreEmptyEvents)
        {
            return;
        }

        boolean changed = offerStartTimes.remove(slot) != null;
        if (offerItems.remove(slot) != null)
        {
            changed = true;
        }
        if (completedOfferTimes.remove(slot) != null)
        {
            changed = true;
        }
        completedOfferStates.remove(slot);
        completedOfferItems.remove(slot);

        if (changed)
        {
            saveTimes();
        }
    }

    /**
     * Seeds start times for any offer that is already live (BUYING/SELLING) but
     * not yet tracked — e.g. when the plugin is enabled while offers are already
     * running. No GE events fire in that case, so without this the offers would
     * never appear. During a normal login the slots are still EMPTY here, so
     * this is a no-op and the events do the work instead.
     */
    private void seedActiveOffersFromClient()
    {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null)
        {
            return;
        }

        boolean changed = false;
        for (int slot = 0; slot < offers.length; slot++)
        {
            GrandExchangeOffer offer = offers[slot];
            if (offer == null)
            {
                continue;
            }

            GrandExchangeOfferState state = offer.getState();
            boolean active = state == GrandExchangeOfferState.BUYING
                    || state == GrandExchangeOfferState.SELLING;
            if (active && offer.getItemId() != 0 && !offerStartTimes.containsKey(slot))
            {
                offerStartTimes.put(slot, Instant.now());
                offerItems.put(slot, offer.getItemId());
                changed = true;
            }
        }

        if (changed)
        {
            saveTimes();
        }
    }

    private void saveTimes()
    {
        for (int i = 0; i < 8; i++)
        {
            // Active offer start time + item id
            if (offerStartTimes.containsKey(i))
            {
                configManager.setRSProfileConfiguration(CONFIG_GROUP, "slot" + i, offerStartTimes.get(i).toEpochMilli());

                Integer item = offerItems.get(i);
                if (item != null)
                {
                    configManager.setRSProfileConfiguration(CONFIG_GROUP, "slotitem" + i, item);
                }
                else
                {
                    configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "slotitem" + i);
                }
            }
            else
            {
                configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "slot" + i);
                configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "slotitem" + i);
            }

            // Completed / cancelled offer data
            if (completedOfferTimes.containsKey(i))
            {
                configManager.setRSProfileConfiguration(CONFIG_GROUP, "completed_time" + i, completedOfferTimes.get(i));
                configManager.setRSProfileConfiguration(CONFIG_GROUP, "completed_state" + i, completedOfferStates.get(i));

                Integer item = completedOfferItems.get(i);
                if (item != null)
                {
                    configManager.setRSProfileConfiguration(CONFIG_GROUP, "completed_item" + i, item);
                }
                else
                {
                    configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "completed_item" + i);
                }
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
            // A slot is either completed or active, never both — completed wins.
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
                    offerStartTimes.remove(i);
                    offerItems.remove(i);
                }
                catch (NumberFormatException e)
                {
                    log.debug("Failed to parse completed offer data for slot {}", i);
                }
                continue;
            }

            String saved = configManager.getRSProfileConfiguration(CONFIG_GROUP, "slot" + i);
            if (saved != null)
            {
                try
                {
                    offerStartTimes.put(i, Instant.ofEpochMilli(Long.parseLong(saved)));

                    String item = configManager.getRSProfileConfiguration(CONFIG_GROUP, "slotitem" + i);
                    if (item != null)
                    {
                        offerItems.put(i, Integer.parseInt(item));
                    }

                    completedOfferTimes.remove(i);
                    completedOfferStates.remove(i);
                    completedOfferItems.remove(i);
                }
                catch (NumberFormatException e)
                {
                    log.debug("Failed to parse saved time for slot {}", i);
                }
            }
            // If the config has nothing for this slot we deliberately leave the
            // in-memory maps alone. That way a load that runs before the profile
            // is ready can't wipe live timers; the overlay's live-offer check and
            // the EMPTY events take care of anything genuinely gone.
        }
    }

    @Provides
    GEOfferTimerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GEOfferTimerConfig.class);
    }
}
