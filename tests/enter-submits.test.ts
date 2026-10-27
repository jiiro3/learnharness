/**
 * enter-submits + focus-hygiene — the pointer/keyboard Enter split and the
 * transient-control blur (owner, keyboard round 2026-10-10).
 *
 * The regression this pins: clicking a transcript control (a thinking
 * toggle) left focus on its <button>; the learner's next Enter re-activated
 * the toggle instead of submitting. The law now: Enter from a
 * POINTER-focused transcript button submits (the window listener also
 * preventDefaults, suppressing the default activation underneath);
 * keyboard-focused buttons and buttons inside open overlays (menus walk
 * focus with arrows, dialogs are their own layer) keep native Enter.
 */
import assert from "node:assert/strict";
import { parseHTML } from "linkedom";
import { describe, it } from "node:test";
import { buttonKeepsNativeEnter, enterSends } from "../components/enter-submits.ts";
import { blurAfterPointerClick } from "../components/focus-hygiene.ts";

const { document } = parseHTML("<html><body></body></html>");

function buttonIn(attrs: Record<string, string>): HTMLElement {
	const host = document.createElement("div");
	for (const [k, v] of Object.entries(attrs)) host.setAttribute(k, v);
	const b = document.createElement("button");
	b.type = "button";
	host.appendChild(b);
	document.body.appendChild(host);
	return b;
}

describe("buttonKeepsNativeEnter (the pointer/keyboard Enter split)", () => {
	it("a transcript button (pointer-focused) does NOT keep native Enter — Enter submits", () => {
		const b = buttonIn({});
		assert.equal(buttonKeepsNativeEnter(b), false);
	});

	it("a keyboard-focused button keeps native Enter (:focus-visible)", () => {
		const b = buttonIn({});
		// linkedom lacks the pseudo — stub the one call the helper makes.
		(b as unknown as { matches: (sel: string) => boolean }).matches = (sel: string) =>
			sel === ":focus-visible";
		assert.equal(buttonKeepsNativeEnter(b), true);
	});

	it("buttons inside overlays keep native Enter: hand-rolled menu, Radix popper, dialog, menu role", () => {
		assert.equal(buttonKeepsNativeEnter(buttonIn({ "data-lh-menu": "" })), true);
		assert.equal(
			buttonKeepsNativeEnter(buttonIn({ "data-radix-popper-content-wrapper": "" })),
			true,
		);
		assert.equal(buttonKeepsNativeEnter(buttonIn({ role: "dialog" })), true);
		assert.equal(buttonKeepsNativeEnter(buttonIn({ role: "menu" })), true);
	});

	it("a selector engine without :focus-visible degrades to pointer-focus, never throws", () => {
		const b = buttonIn({});
		assert.doesNotThrow(() => buttonKeepsNativeEnter(b));
		assert.equal(buttonKeepsNativeEnter(b), false);
	});
});

describe("blurAfterPointerClick (focus hygiene)", () => {
	it("a POINTER click (detail > 0) blurs the transient control", () => {
		const b = buttonIn({});
		let blurred = false;
		b.blur = () => {
			blurred = true;
		};
		blurAfterPointerClick({ detail: 1, currentTarget: b });
		assert.equal(blurred, true);
	});

	it("a KEYBOARD-activated click (detail 0) keeps focus", () => {
		const b = buttonIn({});
		let blurred = false;
		b.blur = () => {
			blurred = true;
		};
		blurAfterPointerClick({ detail: 0, currentTarget: b });
		assert.equal(blurred, false);
	});
});

describe("enterSends (the field-level Enter rule; phone: Enter is a newline)", () => {
	const key = (k: string, mods: Partial<{ shiftKey: boolean; metaKey: boolean; ctrlKey: boolean }> = {}) => ({
		key: k,
		shiftKey: false,
		metaKey: false,
		ctrlKey: false,
		...mods,
	});

	it("desktop: plain Enter sends, Shift+Enter is a newline, ⌘/Ctrl+Enter sends", () => {
		assert.equal(enterSends(key("Enter"), false), true);
		assert.equal(enterSends(key("Enter", { shiftKey: true }), false), false);
		assert.equal(enterSends(key("Enter", { metaKey: true }), false), true);
		assert.equal(enterSends(key("Enter", { ctrlKey: true }), false), true);
		assert.equal(enterSends(key("Enter", { ctrlKey: true, shiftKey: true }), false), true);
	});

	it("phone: plain and Shift+Enter are newlines; only ⌘/Ctrl+Enter sends", () => {
		assert.equal(enterSends(key("Enter"), true), false);
		assert.equal(enterSends(key("Enter", { shiftKey: true }), true), false);
		assert.equal(enterSends(key("Enter", { metaKey: true }), true), true);
		assert.equal(enterSends(key("Enter", { ctrlKey: true }), true), true);
	});

	it("other keys never send", () => {
		assert.equal(enterSends(key("a"), false), false);
		assert.equal(enterSends(key("Escape", { ctrlKey: true }), true), false);
	});

	it("defaults to the live layout gate (no matchMedia in node: desktop)", () => {
		assert.equal(enterSends(key("Enter")), true);
	});
});
