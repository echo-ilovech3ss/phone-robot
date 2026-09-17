package dev.phonerobot.core

import java.util.Locale

enum class Command { EMPTY, INVALID, STOP, SLEEP, WAKE, LEFT, RIGHT, UP, DOWN, CENTER, TRACK, HELLO, HELP, TIME, BATTERY, CHAT }

object CommandRouter {
    private val spaces = Regex("\\s+")
    private val commands = mapOf(
        "stop" to Command.STOP, "emergency stop" to Command.STOP,
        "sleep" to Command.SLEEP, "go to sleep" to Command.SLEEP,
        "wake up" to Command.WAKE, "wake" to Command.WAKE,
        "look left" to Command.LEFT, "look right" to Command.RIGHT,
        "look up" to Command.UP, "look down" to Command.DOWN,
        "look center" to Command.CENTER, "look centre" to Command.CENTER,
        "track me" to Command.TRACK, "follow my face" to Command.TRACK,
        "hello" to Command.HELLO, "hi" to Command.HELLO,
        "help" to Command.HELP, "what can you do" to Command.HELP,
        "what time is it" to Command.TIME, "time" to Command.TIME,
        "battery" to Command.BATTERY, "battery level" to Command.BATTERY,
    )
    fun parse(text: String): Command {
        if (text.length > 1000) return Command.INVALID
        val cleaned = text.trim().trimEnd('.', '!', '?').lowercase(Locale.ROOT).replace(spaces, " ")
        if (cleaned.isBlank()) return Command.EMPTY
        return commands[cleaned] ?: Command.CHAT
    }
    fun needsCloud(command: Command, enabled: Boolean, sleeping: Boolean): Boolean =
        command == Command.CHAT && enabled && !sleeping
}
