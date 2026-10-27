/**
 * The send-failure notice's pure seams (fork: mobile-layout): which errors
 * are the connection's, whether a failed input landed after all (a lost
 * acknowledgement — the retry must not send it twice), the orphan bubble
 * of a failed composer send, and the notice's markup.
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import { inputLanded, isNetworkFailure, orphanTextSend, type SentInput } from "../components/chat.tsx";
import { SendFailureNotice } from "../components/composer.tsx";
import type { LhMessage } from "../src/core/index.ts";

const msgs = (...m: object[]) => m as unknown as LhMessage[];

describe("isNetworkFailure", () => {
	it("recognises the browsers' fetch failures and the stream drop", () => {
		for (const m of [
			"Failed to fetch",
			"NetworkError when attempting to fetch resource.",
			"Load failed",
			"fetch failed",
			"net::ERR_INTERNET_DISCONNECTED",
			"The connection dropped before the turn completed.",
		]) {
			assert.equal(isNetworkFailure(m), true, m);
		}
	});

	it("server and model errors are not the connection's", () => {
		assert.equal(isNetworkFailure("The send failed (500)."), false);
		assert.equal(isNetworkFailure('{"code":"models_not_configured"}'), false);
		assert.equal(isNetworkFailure("A turn is already running — try again in a moment."), false);
		assert.equal(isNetworkFailure(undefined), false);
	});

	it("an offline browser makes any failure a connection failure", () => {
		assert.equal(isNetworkFailure("The send failed (500).", false), true);
	});
});

describe("inputLanded (the retry's lost-acknowledgement check)", () => {
	it("text: a user message with the same text in the slice since the send", () => {
		const input: SentInput = { type: "text", text: "why is the sky blue", at: 1 };
		assert.equal(inputLanded(input, msgs({ id: "u1", role: "user", content: "why is the sky blue" })), true);
		assert.equal(
			inputLanded(input, msgs({ id: "u1", role: "user", content: [{ type: "text", text: "why is the sky blue" }] })),
			true,
		);
		assert.equal(inputLanded(input, msgs({ id: "a1", role: "assistant", content: [] })), false);
		assert.equal(inputLanded(input, msgs()), false);
	});

	it("practice thinking: the marker for the same problem and text", () => {
		const input: SentInput = { type: "thinking", problemCallId: "p1", text: "x = 2", at: 0 };
		const marker = { role: "custom", customType: "practice-thinking", details: { problemCallId: "p1", text: "x = 2" } };
		assert.equal(inputLanded(input, msgs(marker)), true);
		assert.equal(inputLanded(input, msgs({ ...marker, details: { problemCallId: "p2", text: "x = 2" } })), false);
	});

	it("next: any next marker since the send", () => {
		const input: SentInput = { type: "next", at: 0 };
		assert.equal(inputLanded(input, msgs({ role: "custom", customType: "practice-next" })), true);
		assert.equal(inputLanded(input, msgs({ role: "custom", customType: "practice-thinking" })), false);
	});

	it("answers are left to the session model's own record check", () => {
		const input: SentInput = { type: "answer", tool: "quiz", toolCallId: "q1", output: {} as never, at: 0 };
		assert.equal(inputLanded(input, msgs({ role: "user", content: "anything" })), false);
	});
});

describe("orphanTextSend (the failed send's local bubble)", () => {
	it("finds the local c_ copy of the failed text after the send point", () => {
		const native = msgs(
			{ id: "srv_1", role: "user", content: "hello" },
			{ id: "c_abc_1", role: "user", content: "hello" },
		);
		assert.equal(orphanTextSend({ type: "text", text: "hello", at: 1 }, native), "c_abc_1");
	});

	it("never picks a server copy or an earlier message", () => {
		const native = msgs({ id: "c_old", role: "user", content: "hello" }, { id: "srv_2", role: "user", content: "hello" });
		assert.equal(orphanTextSend({ type: "text", text: "hello", at: 1 }, native), null);
		assert.equal(orphanTextSend({ type: "next", at: 0 }, native), null);
	});
});

describe("SendFailureNotice", () => {
	it("one friendly line and a Retry button — never the raw error text", () => {
		const html = renderToStaticMarkup(<SendFailureNotice retrying={false} onRetry={() => {}} />);
		assert.match(html, /role="alert"/);
		assert.match(html, /Couldn(&#x27;|')t connect/);
		assert.match(html, /data-testid="send-failure-retry"/);
		assert.match(html, />Retry</);
		assert.doesNotMatch(html, /Failed to fetch|Something went wrong/);
	});

	it("shows progress while retrying", () => {
		const html = renderToStaticMarkup(<SendFailureNotice retrying onRetry={() => {}} />);
		assert.match(html, /Retrying…/);
		assert.match(html, /disabled/);
	});
});
