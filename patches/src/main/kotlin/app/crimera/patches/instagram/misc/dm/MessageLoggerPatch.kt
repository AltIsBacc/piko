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

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.INTEGRATIONS_PACKAGE
import app.crimera.patches.instagram.utils.Constants.PREF_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

// Smali descriptor for the new extension class.
private const val MSG_LOG = "$INTEGRATIONS_PACKAGE/patches/dm/MessageLog;"

@Suppress("unused")
val messageLoggerPatch =
    bytecodePatch(
        name = "Message logger",
        description = "Logs edited and deleted Direct Messages and emoji reaction changes.",
        // Opt-in only – the user must enable this in the Piko settings screen.
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {

            // ------------------------------------------------------------------
            // 1. Hook message-item binding  (populate cache + detect edits)
            // ------------------------------------------------------------------
            MessageItemBindFingerprint.apply {
                method.apply {
                    /*
                     * Strategy
                     * --------
                     * The bind method calls getString("item_id"), getString("text"),
                     * and getString("item_type") on the JSON object parameter, then
                     * stores each result into a field of the message-model object via
                     * IPUT_OBJECT.  We locate the IPUT_OBJECT instruction that
                     * immediately follows each string match and read back the source
                     * register (index 0 of registersUsed).
                     *
                     * We inject the call to MessageLog.onMessageBound() right after
                     * the "text" field has been stored so that all three registers
                     * are guaranteed to be live and contain their final values.
                     *
                     * ⚠ Register numbers are derived at patch-time from the matched
                     *   instruction stream.  If the compiler lays out registers
                     *   differently (e.g. text is loaded into a wide register or
                     *   reused), inspect the disassembly and add a narrowing cast or
                     *   move before the invoke-static.
                     */
                    val itemIdStrIndex   = stringMatches.first { it.string == "item_id"   }.index
                    val itemTypeStrIndex = stringMatches.first { it.string == "item_type" }.index
                    val textStrIndex     = stringMatches.first { it.string == "text"      }.index

                    val itemIdRegister   = getInstruction(
                        indexOfFirstInstruction(itemIdStrIndex, Opcode.IPUT_OBJECT)
                    ).registersUsed[0]

                    val itemTypeRegister = getInstruction(
                        indexOfFirstInstruction(itemTypeStrIndex, Opcode.IPUT_OBJECT)
                    ).registersUsed[0]

                    val textPutIdx   = indexOfFirstInstruction(textStrIndex, Opcode.IPUT_OBJECT)
                    val textRegister = getInstruction(textPutIdx).registersUsed[0]

                    // Inject immediately after the "text" IPUT_OBJECT so all
                    // three values are settled in their registers.
                    addInstructions(
                        textPutIdx + 1,
                        """
                        invoke-static {v$itemIdRegister, v$textRegister, v$itemTypeRegister}, L$MSG_LOG->onMessageBound(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                        """.trimIndent(),
                    )
                }
            }

            // ------------------------------------------------------------------
            // 2. Hook message deletion
            // ------------------------------------------------------------------
            MessageDeleteFingerprint.apply {
                method.apply {
                    /*
                     * Strategy
                     * --------
                     * The delete method typically receives the message_id as its
                     * first parameter (p1 for a non-static instance method).
                     * We inject at index 0 so the log call fires BEFORE Instagram
                     * removes the item from its internal data structure.
                     *
                     * ⚠ If the method signature differs (e.g. the message_id is
                     *   p2 or passed as a long), adjust the parameter reference
                     *   after APK inspection.
                     */
                    addInstructions(
                        0,
                        """
                        invoke-static {p1}, L$MSG_LOG->onMessageDeleted(Ljava/lang/String;)V
                        """.trimIndent(),
                    )
                }
            }

            // ------------------------------------------------------------------
            // 3. Hook reaction updates
            // ------------------------------------------------------------------
            MessageReactionFingerprint.apply {
                method.apply {
                    /*
                     * Strategy
                     * --------
                     * The reaction method stores the reactions collection into a
                     * field via IPUT_OBJECT (matched by the "reactions" string).
                     * We inject BEFORE that store so we can pass the raw collection
                     * object to Pref.serializeAndLogReactions(), which serialises it
                     * to a stable String and delegates to MessageLog.onReactionUpdate().
                     *
                     * The message_id register is located via the "item_id" string
                     * match present in the same method; we fall back to p1 (the
                     * first instance-method parameter) if "item_id" is absent.
                     *
                     * ⚠ After APK inspection:
                     *   • Confirm the concrete type of the reactions collection and
                     *     update serializeAndLogReactions() to cast + iterate it.
                     *   • Verify the register numbers below; adjust if the compiler
                     *     reuses or widens them.
                     */
                    val reactionStrIdx   = stringMatches.first { it.string == "reactions" }.index
                    val reactionPutIdx   = indexOfFirstInstruction(reactionStrIdx, Opcode.IPUT_OBJECT)
                    val reactionRegister = getInstruction(reactionPutIdx).registersUsed[0]

                    val itemIdRegister: Int = stringMatches
                        .firstOrNull { it.string == "item_id" }
                        ?.let { match ->
                            getInstruction(
                                indexOfFirstInstruction(match.index, Opcode.IPUT_OBJECT)
                            ).registersUsed[0]
                        }
                        ?: 1 // fallback: p1 (first parameter of an instance method)

                    // Inject BEFORE the IPUT_OBJECT so the collection object is
                    // still available in its register.
                    addInstructions(
                        reactionPutIdx,
                        """
                        invoke-static {v$itemIdRegister, v$reactionRegister}, L$PREF_DESCRIPTOR->serializeAndLogReactions(Ljava/lang/String;Ljava/lang/Object;)V
                        """.trimIndent(),
                    )
                }
            }

            // ------------------------------------------------------------------
            // 4. Register all four sub-features in SettingsStatus
            // ------------------------------------------------------------------
            enableSettings("messageLogger")
            enableSettings("messageLoggerEdits")
            enableSettings("messageLoggerDeletes")
            enableSettings("messageLoggerReactions")
        }
    }
