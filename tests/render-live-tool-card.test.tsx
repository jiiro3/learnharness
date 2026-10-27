/**
 * The LIVE tool-input card (owner, streaming round 2026-10-01): what a
 * tool call looks like while the agent writes it — Braille spinner +
 * shimmered activity label in the header (the retired bottom line's
 * text, one glimmer recipe), the streaming input watchable on expand.
 * These pins hold the working-state grammar on the card.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { LiveToolCard } from "../components/artifacts/live-tool-card.tsx";

function part(tool: string, input: unknown) {
	return { type: `tool-${tool}`, toolCallId: "c1", state: "input-streaming", input };
}

describe("LiveToolCard (the working-state grammar)", () => {
	it("spinner + shimmered activity label + headline arg, input on expand", () => {
		const html = renderToStaticMarkup(<LiveToolCard part={part("read", { path: "notes.md" }) as never} />);
		assert.match(html, /data-testid="live-tool-card"/);
		assert.match(html, /data-testid="braille-spinner"/);
		// the label names the ACTUAL tool (the old bottom line's text)
		assert.match(html, /Reading a file…/);
		// the headline argument rides the header
		assert.match(html, /notes\.md/);
		// the streaming input is shown in the expandable body
		assert.match(html, /Input \(streaming\)/);
		assert.match(html, /notes\.md/);
	});

	it("unknown tools stay generic; jobs get their dispatch labels", () => {
		assert.match(renderToStaticMarkup(<LiveToolCard part={part("mystery_tool", {}) as never} />), /Working…/);
		assert.match(
			renderToStaticMarkup(<LiveToolCard part={part("research", { query: "x" }) as never} />),
			/Dispatching research…/,
		);
	});

	it("the graph tools get human labels; the internal session id stays off the header", () => {
		const sessions = renderToStaticMarkup(<LiveToolCard part={part("list_sessions", {}) as never} />);
		assert.match(sessions, /Looking up past sessions…/);
		const props = renderToStaticMarkup(<LiveToolCard part={part("list_propositions", { session: "chat_abc" }) as never} />);
		assert.match(props, /Looking up a past session…/);
		const header = props.split("<summary")[1]?.split("</summary>")[0] ?? "";
		assert.doesNotMatch(header, /chat_abc|list_propositions/);
	});

	it("no input yet renders a placeholder, not an empty pre", () => {
		const html = renderToStaticMarkup(<LiveToolCard part={part("grep", undefined) as never} />);
		assert.match(html, /<pre[^>]*>…<\/pre>/);
	});
});
