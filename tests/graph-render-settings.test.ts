/**
 * The shared graph render-settings copy (fork: mobile-layout,
 * components/graph-render-settings.ts): the graph view's popover and the
 * Settings → Advanced section edit ONE client copy. The load/save rules are
 * the graph view's old ones: a change made before the first load settles is
 * held and sent when it does; a load reply never clobbers a change made
 * after that load began; saves are debounced into one PUT.
 *
 * One sequential test — the store is module state (one per document).
 */

import assert from "node:assert/strict";
import { test } from "node:test";
import { DEFAULT_RENDER_SETTINGS, type GraphRenderSettings } from "../src/core/index.ts";
import { changeRenderSettings, loadRenderSettings } from "../components/graph-render-settings.ts";

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

test("one copy: held saves, no clobber, debounced PUTs", async () => {
	const puts: GraphRenderSettings[] = [];
	let release: ((body: unknown) => void) | null = null;
	const originalFetch = globalThis.fetch;
	globalThis.fetch = (async (_url: string, init?: RequestInit) => {
		if (init?.method === "PUT") {
			puts.push(JSON.parse(String(init.body)) as GraphRenderSettings);
			return new Response("{}");
		}
		// GET: resolves when the test releases it.
		const body = await new Promise<unknown>((r) => (release = r));
		return Response.json(body);
	}) as typeof fetch;
	try {
		let seen: GraphRenderSettings | null = null;
		// 1. A change while the first load is in flight: applied locally at
		//    once, NOT saved yet (the server copy is unknown).
		loadRenderSettings();
		changeRenderSettings((cur) => {
			const next = { ...cur, nodeSize: cur.nodeSize * 2 };
			seen = next;
			return next;
		});
		assert.ok(seen);
		await wait(10);
		assert.equal(puts.length, 0, "held until the load settles");

		// 2. The load reply arrives with an older server copy: it must not
		//    clobber the local change, and the held save goes out.
		release!({ ...DEFAULT_RENDER_SETTINGS, labelSize: 99 });
		await wait(20);
		assert.equal(puts.length, 1, "the held change is sent once the load settles");
		assert.equal(puts[0]!.nodeSize, DEFAULT_RENDER_SETTINGS.nodeSize * 2);
		assert.notEqual(puts[0]!.labelSize, 99, "the stale reply did not clobber");

		// 3. Rapid changes (a slider drag) after the load: one debounced PUT
		//    with the last value; nested sections compose.
		changeRenderSettings((cur) => ({ ...cur, regions: { ...cur.regions, blur: 1 } }));
		changeRenderSettings((cur) => ({ ...cur, regions: { ...cur.regions, blur: 2 } }));
		changeRenderSettings((cur) => ({ ...cur, regions: { ...cur.regions, dilation: 3 } }));
		await wait(50);
		assert.equal(puts.length, 1, "debounced");
		await wait(450);
		assert.equal(puts.length, 2);
		assert.equal(puts[1]!.regions.blur, 2);
		assert.equal(puts[1]!.regions.dilation, 3);
		assert.equal(puts[1]!.nodeSize, DEFAULT_RENDER_SETTINGS.nodeSize * 2, "earlier changes ride along");

		// 4. A reload with no local change since it began applies the server copy.
		let applied = false;
		loadRenderSettings();
		release!({ ...DEFAULT_RENDER_SETTINGS, labelSize: DEFAULT_RENDER_SETTINGS.labelSize });
		await wait(20);
		changeRenderSettings((cur) => {
			applied = cur.nodeSize === DEFAULT_RENDER_SETTINGS.nodeSize;
			return cur; // no-op change: nothing saved
		});
		assert.ok(applied, "the server copy replaced the local one");
		await wait(450);
		assert.equal(puts.length, 2, "an unchanged update never saves");
	} finally {
		globalThis.fetch = originalFetch;
	}
});
