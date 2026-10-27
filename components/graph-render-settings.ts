"use client";

/**
 * THE GRAPH RENDER SETTINGS, SHARED (fork: mobile-layout). One client copy
 * of the server-persisted render settings (data/render-settings.json via
 * /api/graph/render-settings) for every surface that edits them: the
 * graph view's display popover and the Settings dialog's Advanced section
 * (the region tuning knobs moved there). Both read and write THIS copy,
 * so a change in either lands in the renderer live and is saved once.
 *
 * The load/save rules are the graph view's, unchanged:
 * - Every mount of a user reloads from the server (`load`), but a reply
 *   never clobbers a local change made after that load started — if the
 *   user outran the network, their change wins.
 * - Saves (debounced) are held until a load has settled, so merely
 *   mounting never PUTs defaults over the server copy.
 */

import { useEffect, useSyncExternalStore } from "react";
import { DEFAULT_RENDER_SETTINGS, sanitizeRenderSettings, type GraphRenderSettings } from "@/src/core";

export type RenderSettingsUpdate = (cur: GraphRenderSettings) => GraphRenderSettings;

const ENDPOINT = "/api/graph/render-settings";
const SAVE_DEBOUNCE_MS = 400;

let current: GraphRenderSettings = DEFAULT_RENDER_SETTINGS;
const listeners = new Set<() => void>();
/** Bumped by every local change: a load reply applies only when no
 *  change happened since that load began. */
let changeSeq = 0;
let serverLoaded = false;
let savePending = false;
let saveTimer: ReturnType<typeof setTimeout> | null = null;

function emit(): void {
	for (const l of listeners) l();
}

function subscribe(cb: () => void): () => void {
	listeners.add(cb);
	return () => listeners.delete(cb);
}

function getSnapshot(): GraphRenderSettings {
	return current;
}

function saveNow(): void {
	fetch(ENDPOINT, {
		method: "PUT",
		headers: { "content-type": "application/json" },
		body: JSON.stringify(current),
	}).catch((err) => console.error("[graph] settings save failed:", err));
}

/** (Re)load the server copy. */
export function loadRenderSettings(): void {
	const seqAtStart = changeSeq;
	fetch(ENDPOINT)
		.then((res) => (res.ok ? (res.json() as Promise<unknown>) : null))
		.then((raw) => {
			if (raw !== null && changeSeq === seqAtStart) {
				current = sanitizeRenderSettings(raw);
				emit();
			}
		})
		.catch(() => {
			// server unreachable → the in-memory copy keeps working
		})
		.finally(() => {
			serverLoaded = true;
			// A change made before any load settled was held back — send now.
			if (savePending) {
				savePending = false;
				saveNow();
			}
		});
}

/** Apply a user change: live for every subscriber, saved debounced. */
export function changeRenderSettings(update: RenderSettingsUpdate): void {
	const next = update(current);
	if (next === current) return;
	current = next;
	changeSeq++;
	emit();
	if (!serverLoaded) {
		savePending = true;
		return;
	}
	if (saveTimer) clearTimeout(saveTimer);
	saveTimer = setTimeout(() => {
		saveTimer = null;
		saveNow();
	}, SAVE_DEBOUNCE_MS);
}

/** The live settings + the change seam. Loads the server copy on mount. */
export function useRenderSettings(): [GraphRenderSettings, (update: RenderSettingsUpdate) => void] {
	const settings = useSyncExternalStore(subscribe, getSnapshot, () => DEFAULT_RENDER_SETTINGS);
	useEffect(() => {
		loadRenderSettings();
	}, []);
	return [settings, changeRenderSettings];
}
