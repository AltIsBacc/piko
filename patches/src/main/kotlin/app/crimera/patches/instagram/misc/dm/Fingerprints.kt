/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * This file is part of piko.
 *
 * Any modifications, derivatives, or substantial rewrites of this file
 * must retain this copyright notice and the piko attribution
 * in the source code and version control history.
 */

package app.crimera.patches.instagram.misc.dm

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Matches the method inside Instagram's Direct-Message thread layer that
 * parses / binds a single message item from JSON.
 *
 * Heuristic: the method references the string literals "item_id", "text", and
 * "item_type" — all stable JSON field names present in the message-item binder
 * across many Instagram versions.
 *
 * ⚠ Verify these strings against the target APK with jadx / baksmali before
 *   submitting a PR.  Adjust the [strings] list if Instagram renames any of
 *   them, and update the register extraction logic in [MessageLoggerPatch.kt]
 *   accordingly.
 */
internal object MessageItemBindFingerprint : Fingerprint(
    // The binder returns a typed message-model object (Object at the dex level).
    returnType = "Ljava/lang/Object;",
    // Typically a public instance method; adjust if access flags differ.
    accessFlags = AccessFlags.PUBLIC.value,
    strings = listOf("item_id", "text", "item_type"),
    custom = { methodDef, _ ->
        // Extra guard: exclude constructors.
        methodDef.name != "<init>"
    },
)

/**
 * Matches the method that removes a message item from the thread list / adapter.
 *
 * Heuristic: contains the string "delete_item" and is declared in a class whose
 * fully-qualified type contains "Thread" or "DirectThread" (common Instagram
 * naming conventions for the thread-management layer).
 *
 * ⚠ Verify the "delete_item" string literal in the APK.  If Instagram uses a
 *   different string (or none at all), replace [strings] with an opcode-sequence
 *   heuristic or a [definingClass] constraint derived from APK inspection.
 */
internal object MessageDeleteFingerprint : Fingerprint(
    // Deletion is a side-effect; the method returns void.
    returnType = "V",
    strings = listOf("delete_item"),
    custom = { _, classDef ->
        // Restrict the search to thread-controller classes.
        classDef.type.contains("Thread", ignoreCase = true) ||
            classDef.type.contains("DirectThread", ignoreCase = true)
    },
)

/**
 * Matches the method that sets / updates emoji reactions on a message item.
 *
 * Heuristic: references "emoji_reaction_count" or "reactions" — stable JSON
 * field names used by Instagram's reaction subsystem.
 *
 * ⚠ Verify at least one of these string literals in the target APK, and
 *   confirm the concrete Java type of the reactions collection so that
 *   [app.morphe.extension.instagram.utils.Pref.serializeAndLogReactions] can
 *   cast and serialise it correctly.
 */
internal object MessageReactionFingerprint : Fingerprint(
    // Reaction update is a side-effect; the method returns void.
    returnType = "V",
    strings = listOf("emoji_reaction_count", "reactions"),
)
