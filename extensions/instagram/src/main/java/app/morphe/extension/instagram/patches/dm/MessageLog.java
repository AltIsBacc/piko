/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * This file is part of piko.
 *
 * Any modifications, derivatives, or substantial rewrites of this file
 * must retain this copyright notice and the piko attribution
 * in the source code and version control history.
 */

package app.morphe.extension.instagram.patches.dm;

import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.SharedPref;

/**
 * In-process message log.
 *
 * Instagram's Direct-Message layer works with model objects that carry:
 *   - message_id  (String / long)
 *   - text body   (String, may be null for media messages)
 *   - is_sent_by_viewer (boolean)
 *   - item_type   (String: "text", "media", "like", "reel_share", …)
 *
 * This class is called via Smali stubs inserted by the Fingerprint/Patch layer.
 * All methods are static so they can be invoked with {@code invoke-static} in the
 * patched bytecode.
 */
@SuppressWarnings("unused")
public class MessageLog {

    private static final String TAG = "PikoMsgLog";

    /** Stores the last known text for each message_id. */
    private static final ConcurrentHashMap<String, String> textCache = new ConcurrentHashMap<>();

    /** Stores the last known reaction map: "message_id" -> "userId:emoji,userId:emoji,…" */
    private static final ConcurrentHashMap<String, String> reactionCache = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    //  Guard helpers
    // -----------------------------------------------------------------------

    private static boolean logEnabled() {
        return SharedPref.getBooleanPerf(Settings.MESSAGE_LOGGER);
    }

    private static boolean editEnabled() {
        return SharedPref.getBooleanPerf(Settings.MESSAGE_LOGGER_EDITS);
    }

    private static boolean deleteEnabled() {
        return SharedPref.getBooleanPerf(Settings.MESSAGE_LOGGER_DELETES);
    }

    private static boolean reactionsEnabled() {
        return SharedPref.getBooleanPerf(Settings.MESSAGE_LOGGER_REACTIONS);
    }

    // -----------------------------------------------------------------------
    //  Called when a message item is bound / parsed from JSON
    // -----------------------------------------------------------------------

    /**
     * Hook point: called every time Instagram binds a Direct Message item.
     *
     * @param messageId The unique ID of the message (pass as String).
     * @param text      The current text body (may be null for media messages).
     * @param itemType  Instagram's item_type string ("text", "media", …).
     */
    public static void onMessageBound(String messageId, String text, String itemType) {
        if (!logEnabled() || messageId == null) return;

        String safeText = text != null ? text : "[" + itemType + "]";

        if (editEnabled()) {
            String previous = textCache.get(messageId);
            if (previous != null && !previous.equals(safeText)) {
                // Message was edited – log it.
                Log.i(TAG, "EDIT  id=" + messageId
                        + " | was: " + previous
                        + " | now: " + safeText);
                // TODO: surface this to the UI via a notification or an in-thread
                //       ghost row (see §4 – UI surface in the feature spec).
            }
        }

        textCache.put(messageId, safeText);
    }

    // -----------------------------------------------------------------------
    //  Called when a message item is removed from the thread adapter
    // -----------------------------------------------------------------------

    /**
     * Hook point: called just before Instagram removes a message from its
     * RecyclerView adapter / thread model list.
     *
     * @param messageId The unique ID of the deleted message.
     */
    public static void onMessageDeleted(String messageId) {
        if (!logEnabled() || !deleteEnabled() || messageId == null) return;

        String text = textCache.get(messageId);
        if (text != null) {
            Log.i(TAG, "DELETE id=" + messageId + " | text: " + text);
            // TODO: surface this to the UI (see §4 in the feature spec).
        }
        // Do NOT remove from cache – keep for future reference.
    }

    // -----------------------------------------------------------------------
    //  Called when the reaction list on a message changes
    // -----------------------------------------------------------------------

    /**
     * Hook point: called whenever Instagram sets/updates the emoji reactions on
     * a message item.
     *
     * @param messageId   The unique ID of the message.
     * @param reactionKey A serialised snapshot of current reactions,
     *                    e.g. "userId1:❤️,userId2:😂".
     *                    Built by {@link app.morphe.extension.instagram.utils.Pref#serializeAndLogReactions}
     *                    from whatever object Instagram passes.
     */
    public static void onReactionUpdate(String messageId, String reactionKey) {
        if (!logEnabled() || !reactionsEnabled() || messageId == null) return;

        String previous = reactionCache.getOrDefault(messageId, "");
        if (!previous.equals(reactionKey)) {
            Log.i(TAG, "REACTION id=" + messageId
                    + " | was: [" + previous + "]"
                    + " | now: [" + reactionKey + "]");
            reactionCache.put(messageId, reactionKey);
        }
    }

    // -----------------------------------------------------------------------
    //  Utility: expose cached data (for a future UI surface layer)
    // -----------------------------------------------------------------------

    /**
     * Returns the logged text for a message_id, or {@code null} if not cached.
     */
    public static String getCachedText(String messageId) {
        return textCache.get(messageId);
    }

    /**
     * Returns the logged reaction snapshot for a message_id, or an empty
     * string if none has been recorded yet.
     */
    public static String getCachedReactions(String messageId) {
        return reactionCache.getOrDefault(messageId, "");
    }
}
