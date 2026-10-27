import type { AgentMessage, BeforeToolCallContext, BeforeToolCallResult } from "@earendil-works/pi-agent-core";
import { QUESTION_TOOLS } from "../core/pending.ts";
import { SEND_TO_USER_TOOL, userFacingTextOf } from "../core/native-messages.ts";

/**
 * THE FEEDBACK GUARD (teacher-feedback, 2026-10-26): QA found the
 * teacher answering a graded answer only in its reasoning: the
 * correction sat in collapsed thinking and the visible reply was just the
 * next question. The prompt carries the rule (shared/40-formatting.md,
 * teacher/28-predictions.md). This is the cheap structural backstop: a
 * teacher question call is refused, through the ordinary tool-error retry
 * path, while the learner's last graded answer has had no
 * learner-visible reply. Visible means an ordinary text block or a
 * send_to_user message. Reasoning never counts.
 *
 * Scope: teacher only, and only after a graded answer. The prober
 * measures without commenting (its doctrine), practice poses only on
 * the learner's go-ahead, and a question after the learner's own message
 * is never blocked.
 */

const QUESTION_SET = new Set<string>(QUESTION_TOOLS);

/** Refusals per answer before the guard steps aside: a backstop, never a
 *  loop the session can get stuck in (or pay for). */
export const FEEDBACK_GUARD_MAX_REFUSALS = 3;

const REFUSAL_MARK = "Question not posed: his last answer has no written reply yet.";

/** Where the transcript stands relative to the learner's last graded
 *  answer: whether it still awaits a visible reply, and how many times
 *  this guard has refused since. Pure, exported for tests. */
export function answerReplyState(messages: readonly AgentMessage[]): { awaiting: boolean; refusals: number } {
	let refusals = 0;
	for (let i = messages.length - 1; i >= 0; i--) {
		const m = messages[i] as { role?: string; content?: unknown; toolName?: string; isError?: boolean };
		if (m.role === "assistant") {
			if (userFacingTextOf(m.content).length > 0) return { awaiting: false, refusals };
			continue;
		}
		if (m.role === "user") return { awaiting: false, refusals };
		if (m.role === "toolResult" && m.toolName !== undefined && QUESTION_SET.has(m.toolName)) {
			// The graded answer: a question call's clean result. Errored
			// results are the retry loop (this guard's refusals included).
			if (m.isError !== true) return { awaiting: true, refusals };
			if (JSON.stringify(m.content ?? "").includes(REFUSAL_MARK)) refusals++;
		}
		// other tool results, markers, steers: not a boundary, keep scanning
	}
	return { awaiting: false, refusals };
}

/** True when the transcript ends in a graded answer that no visible
 *  assistant text has followed yet. */
export function answerAwaitsVisibleReply(messages: readonly AgentMessage[]): boolean {
	return answerReplyState(messages).awaiting;
}

/** The refusal the model reads (a tool error, retried like a schema miss). */
export function feedbackGuardReason(hasOutputTool: boolean): string {
	const how = hasOutputTool
		? `Call ${SEND_TO_USER_TOOL} now, in this same response and before the question, with that feedback; ordinary text and reasoning do not reach him.`
		: "Write that feedback as visible text before the question; your reasoning does not reach him.";
	return (
		`${REFUSAL_MARK} Acknowledge his answer and give the correction or explanation. ${how} Then ask the question again.`
	);
}

/** The teacher's beforeToolCall hook. */
export async function feedbackGuard(ctx: BeforeToolCallContext): Promise<BeforeToolCallResult | undefined> {
	if (!QUESTION_SET.has(ctx.toolCall.name)) return undefined;
	const messages = ctx.context.messages;
	// The requesting message is normally already in context; include it
	// when it is not, so a same-message send_to_user/text counts.
	const scan = messages.at(-1) === ctx.assistantMessage ? messages : [...messages, ctx.assistantMessage];
	const state = answerReplyState(scan);
	if (!state.awaiting || state.refusals >= FEEDBACK_GUARD_MAX_REFUSALS) return undefined;
	const hasOutputTool = (ctx.context.tools ?? []).some((t) => t.name === SEND_TO_USER_TOOL);
	return { block: true, reason: feedbackGuardReason(hasOutputTool) };
}
