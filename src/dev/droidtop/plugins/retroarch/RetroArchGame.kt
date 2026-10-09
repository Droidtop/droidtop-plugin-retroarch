package dev.droidtop.plugins.retroarch

/**
 * The running game's controls on droidtop's companion screen (droidtop docs/plugin-api.md 3 C15, B8): save and load
 * states, the slot, fast-forward, shaders and RetroArch's own FPS line, each one of RetroArch's network commands
 * (DESIGN.md 5), sent by droidtop (`retroarch.command`) because this plugin has no sockets. After each command droidtop
 * asks RetroArch's status, so a row says what happened ("Saved to slot 3") or that RetroArch did not answer.
 *
 * RetroArch has no command that reports the state slot (command.c `command_get_config_param` knows no `state_slot`),
 * so the slot shown is counted from the slot changes made here, starting from RetroArch's default, slot 0, for each
 * game; a slot changed in RetroArch's own menu is not seen. Pure: the plugin feeds it the answers.
 */
internal object RetroArchGame {
    const val OP_COMMAND = "command"

    const val SAVE_STATE = "SAVE_STATE"
    const val LOAD_STATE = "LOAD_STATE"
    const val SLOT_PLUS = "STATE_SLOT_PLUS"
    const val SLOT_MINUS = "STATE_SLOT_MINUS"
    const val FAST_FORWARD = "FAST_FORWARD"
    const val SHADER_TOGGLE = "SHADER_TOGGLE"
    const val SHADER_NEXT = "SHADER_NEXT"
    const val SHADER_PREV = "SHADER_PREV"
    const val FPS_TOGGLE = "FPS_TOGGLE"

    /** The commands the rows and tiles send; anything else a call names is refused before it reaches droidtop. */
    val COMMANDS = setOf(SAVE_STATE, LOAD_STATE, SLOT_PLUS, SLOT_MINUS, FAST_FORWARD, SHADER_TOGGLE, SHADER_NEXT, SHADER_PREV, FPS_TOGGLE)

    /** RetroArch's own slot range (`state_slot`, -1 auto up to 999); this counts the numbered ones only. */
    const val MAX_SLOT = 999

    /** The slot after [command]: plus and minus move it within 0..[MAX_SLOT]; every other command leaves it. */
    fun slotAfter(slot: Int, command: String): Int = when (command) {
        SLOT_PLUS -> (slot + 1).coerceAtMost(MAX_SLOT)
        SLOT_MINUS -> (slot - 1).coerceAtLeast(0)
        else -> slot
    }

    /** What a row says after [command]: the plain outcome when RetroArch [answered], else that it did not. */
    fun message(command: String, answered: Boolean, slot: Int): String {
        if (!answered) return "No answer from RetroArch"
        return when (command) {
            SAVE_STATE -> "Saved to slot $slot"
            LOAD_STATE -> "Loaded slot $slot"
            SLOT_PLUS, SLOT_MINUS -> "Slot $slot"
            FAST_FORWARD -> "Fast-forward switched"
            SHADER_TOGGLE -> "Shader switched"
            SHADER_NEXT -> "Next shader"
            SHADER_PREV -> "Previous shader"
            FPS_TOGGLE -> "FPS line switched"
            else -> "Sent"
        }
    }
}
