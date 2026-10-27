/**
 * Phone graph fixes (fork: mobile-layout, qa/fix-graph).
 *
 * 1. The camera: persisted as the d3 transform PLUS the viewport size it
 *    was taken in, so the phone restores it viewport-relative (rotation,
 *    the soft keyboard) instead of as raw pixels. Desktop keeps the raw
 *    restore. Pure math pinned here; the renderer is DOM-bound.
 * 2. Search framing: frameTransform caps the zoom (a single match's bbox
 *    is a point) and stays in the scale extent.
 * 3. The SSR pins: SSR renders the desktop tree (useIsPhone's server
 *    snapshot is false), so the desktop accept list, the hidden-by-default
 *    fit button, and the chain-table geometry must read exactly as before.
 */

import React from "react";
(globalThis as Record<string, unknown>).React = React;

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import {
	bboxOnScreen,
	fitTransform,
	frameTransform,
	parseSavedCamera,
	reanchorCamera,
	restoreCamera,
	SCALE_EXTENT,
	type GraphRenderSettings,
} from "../components/graph-canvas.ts";
import { GraphChromeRow, NOTES_ACCEPT, NOTES_ACCEPT_PHONE } from "../components/graph-view.tsx";

/** The world point under the viewport center for transform t. */
const centerOf = (t: { k: number; x: number; y: number }, w: number, h: number) => ({
	x: (w / 2 - t.x) / t.k,
	y: (h / 2 - t.y) / t.k,
});

describe("parseSavedCamera", () => {
	it("accepts a finite transform with k > 0 and keeps a real viewport size", () => {
		assert.deepEqual(parseSavedCamera({ k: 2, x: 10, y: -5, w: 412, h: 800 }), { k: 2, x: 10, y: -5, w: 412, h: 800 });
	});
	it("legacy entries (no size) still parse; a 0×0 or partial size is dropped", () => {
		assert.deepEqual(parseSavedCamera({ k: 1, x: 0, y: 0 }), { k: 1, x: 0, y: 0 });
		assert.deepEqual(parseSavedCamera({ k: 1, x: 0, y: 0, w: 0, h: 0 }), { k: 1, x: 0, y: 0 });
		assert.deepEqual(parseSavedCamera({ k: 1, x: 0, y: 0, w: 400 }), { k: 1, x: 0, y: 0 });
	});
	it("rejects corrupt shapes", () => {
		for (const bad of [null, undefined, 3, "x", {}, { k: 0, x: 0, y: 0 }, { k: -1, x: 0, y: 0 }, { k: 1, x: NaN, y: 0 }, { k: 1, x: 0, y: "0" }, { k: Infinity, x: 0, y: 0 }])
			assert.equal(parseSavedCamera(bad), null, JSON.stringify(bad));
	});
});

describe("reanchorCamera / restoreCamera (viewport-relative camera)", () => {
	const t = { k: 1.5, x: 120, y: -40 };
	it("keeps the same world point under the center across sizes, zoom untouched", () => {
		const before = centerOf(t, 412, 800);
		const rotated = reanchorCamera(t, 412, 800, 900, 380);
		const after = centerOf(rotated, 900, 380);
		assert.equal(rotated.k, t.k);
		assert.ok(Math.abs(before.x - after.x) < 1e-9 && Math.abs(before.y - after.y) < 1e-9);
	});
	it("is the identity for an unchanged size and round-trips (keyboard open → close)", () => {
		assert.deepEqual(reanchorCamera(t, 412, 800, 412, 800), t);
		const open = reanchorCamera(t, 412, 800, 412, 450);
		const closed = reanchorCamera(open, 412, 450, 412, 800);
		assert.ok(Math.abs(closed.x - t.x) < 1e-9 && Math.abs(closed.y - t.y) < 1e-9);
	});
	it("phone (anchored) re-centers a sized entry; desktop and legacy entries stay raw", () => {
		const saved = { ...t, w: 900, h: 380 };
		const phone = restoreCamera(saved, 412, 800, true);
		assert.deepEqual(phone, reanchorCamera(t, 900, 380, 412, 800));
		assert.deepEqual(restoreCamera(saved, 412, 800, false), t, "desktop: raw pixels, unchanged behavior");
		assert.deepEqual(restoreCamera(t, 412, 800, true), t, "no recorded size: raw");
	});
	it("the drift case: a landscape view restored raw in portrait can show nothing; anchored it shows the same center", () => {
		const bbox = { minX: 525, maxX: 535, minY: 25, maxY: 35 };
		// Landscape 900×380, zoomed 2× on the cluster at world ~530,30.
		const land = { k: 2, x: 450 - 530 * 2, y: 190 - 30 * 2 };
		assert.ok(bboxOnScreen(bbox, land, 900, 380));
		const raw = restoreCamera({ ...land, w: 900, h: 380 }, 412, 800, false);
		assert.ok(!bboxOnScreen(bbox, raw, 412, 800), "raw restore: off-screen");
		const anchored = restoreCamera({ ...land, w: 900, h: 380 }, 412, 800, true);
		assert.ok(bboxOnScreen(bbox, anchored, 412, 800), "anchored restore: on-screen");
	});
});

describe("bboxOnScreen", () => {
	const bbox = { minX: 0, maxX: 100, minY: 0, maxY: 100 };
	it("inside, partially inside, and touching all count as visible", () => {
		assert.ok(bboxOnScreen(bbox, { k: 1, x: 0, y: 0 }, 400, 400));
		assert.ok(bboxOnScreen(bbox, { k: 1, x: -90, y: -90 }, 400, 400));
		assert.ok(bboxOnScreen(bbox, { k: 1, x: 400, y: 0 }, 400, 400));
	});
	it("entirely off any side is not visible", () => {
		assert.ok(!bboxOnScreen(bbox, { k: 1, x: -101, y: 0 }, 400, 400));
		assert.ok(!bboxOnScreen(bbox, { k: 1, x: 401, y: 0 }, 400, 400));
		assert.ok(!bboxOnScreen(bbox, { k: 1, x: 0, y: -101 }, 400, 400));
		assert.ok(!bboxOnScreen(bbox, { k: 1, x: 0, y: 401 }, 400, 400));
	});
});

describe("frameTransform (search → the matches)", () => {
	it("a single match (point bbox) centers at maxK, never the infinite fit zoom", () => {
		const t = frameTransform({ minX: 50, maxX: 50, minY: -20, maxY: -20 }, 400, 600, 60, 3);
		assert.equal(t.k, 3);
		const c = centerOf(t, 400, 600);
		assert.ok(Math.abs(c.x - 50) < 1e-9 && Math.abs(c.y + 20) < 1e-9);
	});
	it("spread matches use the fit zoom when it is below maxK", () => {
		const bbox = { minX: 0, maxX: 1000, minY: 0, maxY: 1000 };
		const t = frameTransform(bbox, 400, 600, 60, 3);
		assert.equal(t.k, fitTransform(bbox, 400, 600, 60).k);
	});
	it("clamps into the scale extent and survives a viewport smaller than the padding", () => {
		assert.equal(frameTransform({ minX: 0, maxX: 0, minY: 0, maxY: 0 }, 400, 400, 60, 1e9).k, SCALE_EXTENT[1]);
		assert.equal(frameTransform({ minX: 0, maxX: 1e9, minY: 0, maxY: 1e9 }, 400, 400, 60, 3).k, SCALE_EXTENT[0]);
		const tiny = frameTransform({ minX: 0, maxX: 10, minY: 0, maxY: 10 }, 50, 50, 60, 100);
		assert.ok(tiny.k > 0 && Number.isFinite(tiny.k));
	});
});

describe("notes picker accept list", () => {
	it("desktop keeps the extension-only list; phone adds the matching MIME types", () => {
		assert.equal(NOTES_ACCEPT, ".md,.markdown,.txt");
		const phone = NOTES_ACCEPT_PHONE.split(",");
		for (const v of [".md", ".markdown", ".txt", "text/markdown", "text/x-markdown", "text/plain"]) assert.ok(phone.includes(v), v);
	});
});

describe("SSR renders the desktop tree unchanged", () => {
	const ref = { current: null };
	const PROPS = {
		query: "",
		onQuery: () => {},
		noMatch: false,
		importing: false,
		searchInputRef: ref,
		fileInputRef: ref,
		onImportFiles: () => {},
		settings: {} as GraphRenderSettings,
		changeSettings: () => {},
		onOpenChains: () => {},
		onRecenter: () => {},
		onSearchSubmit: () => {},
	};
	it("the import input keeps the desktop accept list", () => {
		const html = renderToStaticMarkup(createElement(GraphChromeRow, { ...PROPS, isEmpty: false }));
		assert.ok(html.includes(`accept="${NOTES_ACCEPT}"`));
		assert.ok(!html.includes("text/markdown"));
	});
	it("the fit control is phone-only (hidden until the phone variant) and absent on an empty graph", () => {
		const html = renderToStaticMarkup(createElement(GraphChromeRow, { ...PROPS, isEmpty: false }));
		assert.match(html, /class="graph-chrome-fade-in hidden shrink-0 phone:block"><button[^>]*data-testid="graph-recenter"/);
		assert.match(html, /data-testid="graph-recenter"[^>]*phone:size-11/);
		const empty = renderToStaticMarkup(createElement(GraphChromeRow, { ...PROPS, isEmpty: true }));
		assert.ok(!empty.includes("graph-recenter"));
	});
});
