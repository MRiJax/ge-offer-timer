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
    private static final int SLOTS = 8;

    // After (re)login or a world hop the client replays a burst of GE offer
    // events to sync each slot. During that burst the offer array is not yet
    // trustworthy, so we wait this many game ticks (~0.6s each) before reading
    // it. This is also long enough for the per-account "RS profile" config to
    // be resolved, which is what makes saved timers reload after a full restart.
    private static final int LOGIN_GRACE_TICKS = 3;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GETimerOverlay overlay;

    @Inject
    private ConfigManager configManager;

    @Inject
    private Client client;

    // Active offers: slot -> when the timer started, and the item it is for.
    final Map<Integer, Instant> offerStartTimes = new HashMap<>();
    private final Map<Integer, Integer> offerItemIds = new HashMap<>();

    // Completed/cancelled offers waiting to be collected: frozen elapsed time,
    // a label ("BOUGHT" / "SOLD" / "CANCELLED") and the item id, per slot.
    final Map<Integer, Long> completedOfferTimes = new HashMap<>();
    final Map<Integer, String> completedOfferStates = new HashMap<>();
    final Map<Integer, Integer> completedOfferItems = new HashMap<>();

    private int ticksSinceLogin = 0;
    private boolean restored = false;

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(overlay);
        // Do not load here: at start-up we are on the login screen, where the
        // RS profile is not resolved yet and config reads return nothing.
        // The first reconcile after login (see onGameTick) restores instead.
        resetSessionState();
        log.debug("GE Offer Timer started!");
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        saveTimes();
        offerStartTimes.clear();
        offerItemIds.clear();
        completedOfferTimes.clear();
        completedOfferStates.clear();
        completedOfferItems.clear();
        resetSessionState();
        log.debug("GE Offer Timer stopped!");
    }

    private void resetSessionState()
    {
        ticksSinceLogin = 0;
        restored = false;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOGGED_IN)
        {
            // Fresh login or the end of a world hop: re-arm the grace window so
            // we re-validate against this world's offers before touching state.
            resetSessionState();
        }
        else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            // Best-effort persist on the way out. In-play reconciles already
            // saved every change, so this is just belt-and-suspenders.
            saveTimes();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        // Let the login/hop offer burst settle (and the RS profile resolve)
        // before we trust the offer array.
        if (ticksSinceLogin < LOGIN_GRACE_TICKS)
        {
            ticksSinceLogin++;
            return;
        }

        if (!restored)
        {
            loadSavedTimes();
            restored = true;
            log.debug("Login grace elapsed; restored saved GE timers");
        }

        reconcileAll();
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        log.debug("GE offer changed - slot: {} state: {}", event.getSlot(), event.getOffer().getState());

        // Ignore the login/hop sync burst entirely; onGameTick restores and
        // reconciles once the grace window has elapsed. After that, handle the
        // single changed slot immediately so the overlay reacts without waiting
        // for the next tick.
        if (client.getGameState() != GameState.LOGGED_IN || !restored)
        {
            return;
        }

        if (reconcileSlot(event.getSlot(), client.getGrandExchangeOffers()))
        {
            saveTimes();
        }
    }

    /**
     * Brings every slot's remembered state in line with what the game actually
     * reports right now. This is the single source of truth: it starts timers
     * for new offers, freezes completed ones, and drops anything for a slot the
     * game says is empty (which also clears collected offers and stale ghosts).
     */
    private void reconcileAll()
    {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null)
        {
            return;
        }

        boolean changed = false;
        for (int slot = 0; slot < offers.length && slot < SLOTS; slot++)
        {
            changed |= reconcileSlot(slot, offers);
        }

        if (changed)
        {
            saveTimes();
        }
    }

    /**
     * Reconciles a single slot against its live offer. Returns true if anything
     * we remember for that slot changed (so the caller can persist).
     */
    private boolean reconcileSlot(int slot, GrandExchangeOffer[] offers)
    {
        if (offers == null || slot < 0 || slot >= offers.length)
        {
            return false;
        }

        GrandExchangeOffer offer = offers[slot];
        GrandExchangeOfferState state = offer == null ? GrandExchangeOfferState.EMPTY : offer.getState();
        int itemId = offer == null ? 0 : offer.getItemId();
        boolean changed = false;

        switch (state)
        {
            case BUYING:
            case SELLING:
            {
                // Active: ensure a running timer and drop any stale completed row.
                if (completedOfferTimes.remove(slot) != null)
                {
                    completedOfferStates.remove(slot);
                    completedOfferItems.remove(slot);
                    changed = true;
                }

                if (!offerStartTimes.containsKey(slot))
                {
                    // A newly placed offer (or one we are seeing for the first
                    // time this session) — start counting now.
                    offerStartTimes.put(slot, Instant.now());
                    offerItemIds.put(slot, itemId);
                    changed = true;
                }
                else
                {
                    Integer knownItem = offerItemIds.get(slot);
                    if (knownItem == null)
                    {
                        // Restored from older saved data that had no item id —
                        // adopt it without resetting the timer.
                        offerItemIds.put(slot, itemId);
                        changed = true;
                    }
                    else if (knownItem != itemId)
                    {
                        // A different offer now occupies this slot, so the saved
                        // start time belongs to a finished offer — restart.
                        offerStartTimes.put(slot, Instant.now());
                        offerItemIds.put(slot, itemId);
                        changed = true;
                    }
                }
                break;
            }

            case BOUGHT:
            case SOLD:
            case CANCELLED_BUY:
            case CANCELLED_SELL:
            {
                // Completed: freeze the timer once, if we were tracking it.
                Instant start = offerStartTimes.get(slot);
                if (start != null)
                {
                    long elapsed = Instant.now().toEpochMilli() - start.toEpochMilli();
                    completedOfferTimes.put(slot, elapsed);
                    completedOfferStates.put(slot, completedLabel(state));
                    completedOfferItems.put(slot, itemId);
                    offerStartTimes.remove(slot);
                    offerItemIds.remove(slot);
                    changed = true;
                }
                // Otherwise it is either already frozen (kept across login via
                // loadSavedTimes) or one we never timed — leave it untouched.
                break;
            }

            case EMPTY:
            {
                // The slot is genuinely empty (collected, cancelled-and-collected
                // or never used) — forget everything we held for it.
                if (offerStartTimes.remove(slot) != null)
                {
                    offerItemIds.remove(slot);
                    changed = true;
                }
                if (completedOfferTimes.remove(slot) != null)
                {
                    completedOfferStates.remove(slot);
                    completedOfferItems.remove(slot);
                    changed = true;
                }
                break;
            }

            default:
                // Unknown/transitional state — do not touch remembered data.
                break;
        }

        return changed;
    }

    private static String completedLabel(GrandExchangeOfferState state)
    {
        if (state == GrandExchangeOfferState.BOUGHT)
        {
            return "BOUGHT";
        }
        if (state == GrandExchangeOfferState.SOLD)
        {
            return "SOLD";
        }
        return "CANCELLED";
    }

    private void saveTimes()
    {
        for (int i = 0; i < SLOTS; i++)
        {
            // Active offer start time + item id (item id lets us detect a slot
            // being reused by a different offer across sessions).
            if (offerStartTimes.containsKey(i))
            {
                configManager.setRSProfileConfiguration(CONFIG_GROUP, "slot" + i, offerStartTimes.get(i).toEpochMilli());
                Integer item = offerItemIds.get(i);
                if (item != null)
                {
                    configManager.setRSProfileConfiguration(CONFIG_GROUP, "slot_item" + i, item);
                }
                else
                {
                    configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "slot_item" + i);
                }
            }
            else
            {
                configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "slot" + i);
                configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "slot_item" + i);
            }

            // Completed/cancelled offer data.
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
        for (int i = 0; i < SLOTS; i++)
        {
            // Active offer start time + item id.
            String saved = configManager.getRSProfileConfiguration(CONFIG_GROUP, "slot" + i);
            if (saved != null)
            {
                try
                {
                    offerStartTimes.put(i, Instant.ofEpochMilli(Long.parseLong(saved)));
                    String item = configManager.getRSProfileConfiguration(CONFIG_GROUP, "slot_item" + i);
                    if (item != null)
                    {
                        offerItemIds.put(i, Integer.parseInt(item));
                    }
                }
                catch (NumberFormatException e)
                {
                    log.debug("Failed to parse saved time for slot {}", i);
                }
            }

            // Completed/cancelled offer data.
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
