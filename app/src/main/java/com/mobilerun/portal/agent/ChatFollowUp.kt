package com.mobilerun.portal.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * The chat checks every task it started. When one finishes, ChatBrain looks at the result (and the final
 * screenshot) against what the user asked: done = it answers the user; not right = it says what went
 * wrong and starts the task again with a corrected instruction (the planner plans again, the executor
 * runs again), up to maxRounds attempts. A task the user stopped is left alone.
 */
class ChatFollowUp(
    private val chats: ChatStore,
    private val runs: (String) -> RunRecord?,
    private val brain: () -> ChatBrain?,
    private val start: (String) -> String?, // starts a task, returns its uuid (null = it did not start)
    private val runner: (Runnable) -> Unit = { Thread(it, "FaReview").start() },
    private val maxRounds: Int = MAX_ROUNDS,
    private val onChange: () -> Unit = {},
    private val onFinal: (chatId: String, text: String) -> Unit = { _, _ -> },
) : RunListener {
    private data class Tracked(val chatId: String, val round: Int)

    private val tracked = ConcurrentHashMap<String, Tracked>()
    private val reviewing = ConcurrentHashMap.newKeySet<String>()

    /** A task this chat started (round 1), so its result gets checked. */
    fun track(uuid: String, chatId: String, round: Int = 1) {
        tracked[uuid] = Tracked(chatId, round)
    }

    /** Is the assistant checking a result in this chat right now? */
    fun isReviewing(chatId: String) = chatId in reviewing

    /** Runs started from a chat: the bubble waits for the check instead of announcing the raw result. */
    fun isTracked(uuid: String) = tracked.containsKey(uuid)

    override fun started(spec: RunSpec, origin: String) = Unit

    override fun event(uuid: String, kind: String, text: String, steps: Int?) = Unit

    override fun finished(uuid: String, status: RunStatus, result: String, steps: Int, shot: String?) {
        val t = tracked.remove(uuid) ?: return
        if (status == RunStatus.STOPPED) return // the user stopped it: nothing to fix
        reviewing += t.chatId
        onChange()
        runner(Runnable { review(t, shot) })
    }

    private fun review(t: Tracked, shot: String?) {
        val reply = try {
            val b = brain() ?: throw LlmError("The AI is not reachable right now.")
            val lines = chats.chats().firstOrNull { it.id == t.chatId }?.lines.orEmpty()
            b.review(lines, runs, shot, t.round, maxRounds)
        } catch (e: LlmError) {
            BrainReply.Say("I couldn't check the result: ${e.message}")
        } catch (e: Exception) {
            BrainReply.Say("I couldn't check the result: ${e.message}")
        } finally {
            reviewing -= t.chatId
        }
        when (reply) {
            is BrainReply.Say -> {
                chats.add(t.chatId, chats.line("assistant", reply.text))
                onFinal(t.chatId, reply.text)
            }
            is BrainReply.Run -> {
                chats.add(t.chatId, chats.line("assistant", reply.say))
                val uuid = start(reply.instruction)
                if (uuid == null) {
                    chats.add(t.chatId, chats.line("assistant", "I couldn't start the next try."))
                } else {
                    track(uuid, t.chatId, t.round + 1)
                    chats.add(t.chatId, chats.line("task", reply.instruction, run = uuid))
                }
            }
        }
        onChange()
    }

    companion object {
        const val MAX_ROUNDS = 3
    }
}
