/**
 * Settings legibility (qa/fix-settings, 2026-10-26): the on/off switch
 * reads as on or off at a glance (solid vs hollow), every prompt switch
 * carries an On/Off word that never dims, and the "Mode — Coming soon"
 * placeholder row is hidden on phone.
 */
import React, { act } from "react";
(globalThis as Record<string, unknown>).React = React;
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseHTML } from "linkedom";
import { renderToStaticMarkup } from "react-dom/server";
import { Toggle } from "../components/settings/controls.tsx";
import { AgentSection } from "../components/settings/agent-section.tsx";
import { AppearanceSection } from "../components/settings/appearance-section.tsx";

function tagOf(html: string, testid: string): string {
	const i = html.indexOf(`data-testid="${testid}"`);
	assert.ok(i >= 0, `missing ${testid}`);
	return html.slice(html.lastIndexOf("<", i), html.indexOf(">", i) + 1);
}

describe("Toggle on/off legibility", () => {
	it("on is a solid filled pill, off is a hollow outline", () => {
		const on = tagOf(renderToStaticMarkup(<Toggle checked label="x" onChange={() => {}} />), "toggle-x");
		const off = tagOf(renderToStaticMarkup(<Toggle checked={false} label="x" onChange={() => {}} />), "toggle-x");
		assert.match(on, /bg-muted-foreground/);
		assert.match(on, /border-muted-foreground(?!\/)/);
		assert.match(off, /bg-transparent/);
		assert.ok(!/\bbg-muted-foreground\b(?!\/)/.test(off), "off track is not filled");
	});
});

describe("Mode row on phone", () => {
	it("is hidden on phone and still rendered (dimmed) on desktop", () => {
		const html = renderToStaticMarkup(<AppearanceSection appearance={undefined} onSectionChange={() => {}} />);
		const row = tagOf(html, "row-mode");
		assert.match(row, /phone:hidden/);
		assert.match(row, /opacity-40/);
	});
});

describe("Prompt switches carry an On/Off word", () => {
	it("on / off-with-file / off-without-file each say their state; only the switch dims", async () => {
		const dom = parseHTML("<html><body><div id='root'></div></body></html>");
		const globals = globalThis as Record<string, unknown>;
		const previous = new Map<string, PropertyDescriptor | undefined>();
		const seg = (path: string, enabled: boolean, overrideText: string | null) => ({
			path,
			sourceText: "source",
			overrideText,
			enabled,
			shared: false,
		});
		const bodies: Record<string, unknown> = {
			"/api/search": { available: false, engine: null, reason: "none" },
			"/api/keys": { keys: [] },
			"/api/models": { models: [], slots: { strong: null, cheap: null, vision: null } },
			"/api/settings/prompts": {
				prober: [],
				teacher: [seg("teacher/a.md", true, "mine"), seg("teacher/b.md", false, "mine"), seg("teacher/c.md", false, null)],
			},
		};
		for (const [key, value] of Object.entries({
			window: dom.window,
			document: dom.document,
			navigator: globals.navigator ?? { userAgent: "", platform: "" },
			HTMLElement: dom.window.HTMLElement,
			IS_REACT_ACT_ENVIRONMENT: true,
			fetch: async (url: string) => new Response(JSON.stringify(bodies[url] ?? {})),
		})) {
			previous.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
			Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
		}
		const { createRoot } = await import("react-dom/client");
		const root = createRoot(document.getElementById("root")!);
		try {
			await act(async () => {
				root.render(
					<AgentSection
						agent={{ prompts: { "teacher/a.md": true } }}
						models={undefined}
						graph={undefined}
						compaction={undefined}
						onSectionChange={() => {}}
						onModelsChange={() => {}}
						onCompactionChange={() => {}}
					/>,
				);
			});
			const state = (p: string) => document.querySelector<HTMLElement>(`[data-testid="prompt-state-${p}"]`)!;
			assert.equal(state("teacher/a.md").textContent, "On");
			assert.equal(state("teacher/b.md").textContent, "Off");
			assert.equal(state("teacher/c.md").textContent, "Off");
			// The word never dims, even beside the locked (no-file) switch.
			for (const p of ["teacher/a.md", "teacher/b.md", "teacher/c.md"]) {
				assert.ok(!state(p).className.includes("opacity"), `${p} state word stays full contrast`);
				assert.ok(!state(p).parentElement!.className.includes("opacity"));
			}
			const sw = (p: string) => document.querySelector<HTMLButtonElement>(`[data-testid="toggle-prompt-${p.replace(/[^a-z0-9]+/g, "-")}"]`)!;
			assert.equal(sw("teacher/c.md").hasAttribute("disabled"), true, "no override file = locked switch");
			assert.ok(sw("teacher/c.md").parentElement!.className.includes("opacity-40"), "only the switch dims");
			assert.equal(sw("teacher/a.md").getAttribute("aria-checked"), "true");
		} finally {
			act(() => {
				root.unmount();
			});
			for (const [key, descriptor] of previous) {
				if (descriptor) Object.defineProperty(globalThis, key, descriptor);
				else delete globals[key];
			}
		}
	});
});
