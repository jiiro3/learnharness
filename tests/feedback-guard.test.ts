/**
 * The feedback guard (teacher-feedback, 2026-10-26): QA found the teacher
 * answering a graded answer only in its reasoning — the visible reply was
 * the next question. A teacher question after a graded answer is refused
 * (ordinary tool-error retry) until a visible reply exists. Closed-network
 * faux engines only.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createFauxCore, fauxAssistantMessage, fauxText, fauxThinking, fauxToolCall } from "@earendil-works/pi-ai";
import type { AgentMessage } from "@earendil-works/pi-agent-core";
import { FEEDBACK_GUARD_MAX_REFUSALS, answerAwaitsVisibleReply, feedbackGuard, feedbackGuardReason } from "../src/agent/feedback-guard.ts";
import { createProberAgent, createTeacherAgent, bareQuestionTools } from "../src/agent/teacher-agent.ts";
import type { PiEngine } from "../src/agent/models.ts";

const quizArgs = {
	question: "What is $2+2$?",
	options: [{ label: "$4$" }, { label: "$5$" }],
	explanation: "Counting.",
	correctAnswer: "$4$",
	nodeId: "n",
	type: "check",
};

function assistant(content: unknown[]): AgentMessage {
	return { role: "assistant", content, stopReason: "toolUse", timestamp: 1 } as unknown as AgentMessage;
}
function result(toolName: string, isError = false): AgentMessage {
	return {
		role: "toolResult", toolCallId: `${toolName}-1`, toolName, isError, timestamp: 1,
		content: [{ type: "text", text: '{"kind":"quiz","correct":false}' }],
	} as unknown as AgentMessage;
}
function user(text: string): AgentMessage {
	return { role: "user", content: text, timestamp: 1 } as unknown as AgentMessage;
}
const sendToUser = (message: string) => ({ type: "toolCall", id: "s1", name: "send_to_user", arguments: { message } });

describe("answerAwaitsVisibleReply", () => {
	const asked = assistant([fauxText("Teaching."), fauxToolCall("quiz", quizArgs, { id: "quiz-1" })]);
	it("a graded answer with nothing after it awaits a reply", () => {
		assert.equal(answerAwaitsVisibleReply([user("go"), asked, result("quiz")]), true);
	});
	it("thinking alone is not a reply", () => {
		assert.equal(answerAwaitsVisibleReply([asked, result("quiz"), assistant([fauxThinking("You were wrong because…")])]), true);
	});
	it("visible text or a send_to_user message is a reply", () => {
		assert.equal(answerAwaitsVisibleReply([asked, result("quiz"), assistant([fauxText("Not quite: …")])]), false);
		assert.equal(answerAwaitsVisibleReply([asked, result("quiz"), assistant([sendToUser("Not quite: …")])]), false);
	});
	it("errored results (retries) and other tools never count as the answer; a learner message ends the scan", () => {
		assert.equal(answerAwaitsVisibleReply([user("go"), asked, result("quiz", true)]), false);
		assert.equal(answerAwaitsVisibleReply([user("go"), assistant([fauxToolCall("read", {})]), result("read")]), false);
		assert.equal(answerAwaitsVisibleReply([asked, result("quiz"), assistant([fauxText("Right.")]), user("next please")]), false);
		// a retry after the guard's own refusal still awaits the reply
		assert.equal(
			answerAwaitsVisibleReply([asked, result("quiz"), assistant([fauxToolCall("quiz", quizArgs)]), result("quiz", true)]),
			true,
		);
	});
});

describe("feedbackGuard", () => {
	const ctx = (messages: AgentMessage[], name = "quiz", tools: Array<{ name: string }> = []) => {
		const assistantMessage = messages.at(-1) as never;
		return {
			assistantMessage,
			toolCall: { type: "toolCall", id: "q2", name, arguments: {} },
			args: {},
			context: { messages, tools },
		} as never;
	};
	const history = [user("go"), assistant([fauxText("T."), fauxToolCall("quiz", quizArgs)]), result("quiz")];

	it("blocks a bare question after an answer, naming the output channel the model holds", async () => {
		const bare = await feedbackGuard(ctx([...history, assistant([fauxThinking("Wrong."), fauxToolCall("quiz", quizArgs)])]));
		assert.deepEqual(bare, { block: true, reason: feedbackGuardReason(false) });
		assert.match(feedbackGuardReason(false), /visible text/);
		const claude = await feedbackGuard(
			ctx([...history, assistant([fauxToolCall("quiz", quizArgs)])], "quiz", [{ name: "send_to_user" }]),
		);
		assert.match((claude as { reason: string }).reason, /send_to_user/);
	});

	it("lets the question through once the same message carries visible feedback", async () => {
		assert.equal(await feedbackGuard(ctx([...history, assistant([sendToUser("Not quite."), fauxToolCall("quiz", quizArgs)])])), undefined);
		assert.equal(await feedbackGuard(ctx([...history, assistant([fauxText("Not quite."), fauxToolCall("quiz", quizArgs)])])), undefined);
	});

	it("steps aside after the refusal cap, so a stubborn model can never loop the session", async () => {
		const refusal = {
			...result("quiz", true),
			content: [{ type: "text", text: feedbackGuardReason(true) }],
		} as AgentMessage;
		const refused: AgentMessage[] = [];
		for (let i = 0; i < FEEDBACK_GUARD_MAX_REFUSALS; i++) refused.push(assistant([fauxToolCall("quiz", quizArgs)]), refusal);
		const last = assistant([fauxToolCall("quiz", quizArgs)]);
		assert.ok(await feedbackGuard(ctx([...history, ...refused.slice(0, -2), last])), "below the cap: still refused");
		assert.equal(await feedbackGuard(ctx([...history, ...refused, last])), undefined, "at the cap: let through");
	});

	it("never touches non-question tools", async () => {
		assert.equal(await feedbackGuard(ctx([...history, assistant([fauxToolCall("research", {})])], "research")), undefined);
	});
});

describe("the guard in a live teacher loop (faux engine)", () => {
	function scripted(steps: Parameters<ReturnType<typeof createFauxCore>["setResponses"]>[0]) {
		const faux = createFauxCore({ provider: "faux", models: [{ id: "faux-teacher" }] });
		faux.setResponses(steps);
		const engine: PiEngine = { model: faux.getModel(), streamFn: faux.streamSimple } as PiEngine;
		return engine;
	}

	it("a bare check after an answer errors back; the model writes feedback and re-asks", async () => {
		const engine = scripted([
			fauxAssistantMessage([fauxThinking("He picked 5; explain."), fauxToolCall("quiz", quizArgs, { id: "q-bare" })], { stopReason: "toolUse" }),
			fauxAssistantMessage([fauxText("Not quite: $2+2=4$."), fauxToolCall("quiz", quizArgs, { id: "q-ok" })], { stopReason: "toolUse" }),
		]);
		const agent = createTeacherAgent(undefined, {
			engine,
			tools: bareQuestionTools(),
			messages: [user("go"), assistant([fauxText("T."), fauxToolCall("quiz", quizArgs, { id: "quiz-1" })]), { ...result("quiz"), toolCallId: "quiz-1" } as AgentMessage],
		});
		await agent.continue();
		const results = agent.state.messages.filter((m) => m.role === "toolResult") as Array<{ toolCallId: string; isError: boolean; content: Array<{ text?: string }> }>;
		const bare = results.find((r) => r.toolCallId === "q-bare")!;
		assert.equal(bare.isError, true);
		assert.match(bare.content[0]!.text ?? "", /no written reply/);
		assert.equal(results.find((r) => r.toolCallId === "q-ok")!.isError, false);
	});

	it("the prober is unguarded (measurement without commentary is its doctrine)", () => {
		const prober = createProberAgent({} as never, { engine: scripted([]), tools: bareQuestionTools() });
		assert.equal(prober.beforeToolCall, undefined);
		assert.equal(createTeacherAgent(undefined, { engine: scripted([]) }).beforeToolCall, feedbackGuard);
	});
});
