"use client";

import { useEffect, useSyncExternalStore } from "react";

/**
 * THE PHONE LAYOUT GATE (fork: mobile-layout). One media query decides
 * whether the shell renders the phone layout (bottom tabs, full-screen
 * sessions, sheet overlays) or the unchanged desktop layout.
 *
 * - Below 640px: always phone. 640 is the Electron window floor
 *   (electron/main.cjs), so the desktop app can never cross it.
 * - Coarse primary pointer below 1024px: phone too (a phone in landscape,
 *   a small tablet).
 * - Never inside Electron (the .lh-electron class, set after mount): a
 *   touchscreen laptop keeps the desktop layout.
 *
 * The CSS twin is the `phone:` variant in app/globals.css. Keep the two
 * in sync. CSS carries everything that must be right on the first paint
 * (no hydration flash); this hook carries structure that only JS can
 * decide (sheet vs popup, autofocus, the sidebar's open state).
 */
export const PHONE_QUERY = "(max-width: 639.98px), (pointer: coarse) and (max-width: 1023.98px)";

const IS_ELECTRON = typeof navigator !== "undefined" && navigator.userAgent.includes("Electron");

const canQuery = () => typeof window !== "undefined" && typeof window.matchMedia === "function" && !IS_ELECTRON;

function subscribe(cb: () => void): () => void {
	if (!canQuery()) return () => {};
	const mql = window.matchMedia(PHONE_QUERY);
	mql.addEventListener("change", cb);
	return () => mql.removeEventListener("change", cb);
}

function snapshot(): boolean {
	if (!canQuery()) return false;
	return window.matchMedia(PHONE_QUERY).matches;
}

/** Non-hook read for event handlers and effects. */
export function isPhoneNow(): boolean {
	return snapshot();
}

/** True while the phone layout applies. The server snapshot is false, so
 *  SSR and the hydration pass render the desktop tree; the phone value
 *  lands on the next client render. */
export function useIsPhone(): boolean {
	return useSyncExternalStore(subscribe, snapshot, () => false);
}

/**
 * Publishes the visual viewport as CSS variables on <html> so the phone
 * shell can stay pinned above the soft keyboard whichever way the host
 * resizes:
 * - --lh-vv-h / --lh-vv-top: the visual viewport's height and offset.
 *   With interactive-widget=resizes-content (app/layout.tsx) they match
 *   the layout viewport; in a WebView that only pans (adjustPan, or a
 *   browser that ignores the meta) they track the visible band instead.
 * - .lh-kb-open: set while the keyboard is up (the viewport is clearly
 *   shorter than the tallest height seen at this width). The bottom tabs
 *   hide and the safe-area bottom inset drops to zero while it is set.
 *
 * Installed once by the app shell; inert outside the phone layout.
 */
export function useVisualViewportVars(active: boolean): void {
	useEffect(() => {
		const root = document.documentElement;
		const vv = window.visualViewport;
		if (!active) {
			root.style.removeProperty("--lh-vv-h");
			root.style.removeProperty("--lh-vv-top");
			root.classList.remove("lh-kb-open", "lh-phone");
			return;
		}
		root.classList.add("lh-phone");
		let maxH = 0;
		let lastW = 0;
		let raf = 0;
		const apply = () => {
			raf = 0;
			const h = vv ? vv.height : window.innerHeight;
			const top = vv ? vv.offsetTop : 0;
			const w = window.innerWidth;
			// Rotation (or a split-screen resize) resets the baseline.
			if (Math.abs(w - lastW) > 1) {
				lastW = w;
				maxH = 0;
			}
			maxH = Math.max(maxH, h, window.innerHeight);
			root.style.setProperty("--lh-vv-h", `${Math.round(h)}px`);
			root.style.setProperty("--lh-vv-top", `${Math.round(top)}px`);
			// A keyboard takes well over 150px; browser toolbars collapsing
			// and the gesture bar never do.
			const editing = isEditable(document.activeElement);
			root.classList.toggle("lh-kb-open", editing && h < maxH - 150);
		};
		const schedule = () => {
			if (!raf) raf = requestAnimationFrame(apply);
		};
		apply();
		vv?.addEventListener("resize", schedule);
		vv?.addEventListener("scroll", schedule);
		window.addEventListener("resize", schedule);
		document.addEventListener("focusin", schedule);
		document.addEventListener("focusout", schedule);
		return () => {
			if (raf) cancelAnimationFrame(raf);
			vv?.removeEventListener("resize", schedule);
			vv?.removeEventListener("scroll", schedule);
			window.removeEventListener("resize", schedule);
			document.removeEventListener("focusin", schedule);
			document.removeEventListener("focusout", schedule);
			root.style.removeProperty("--lh-vv-h");
			root.style.removeProperty("--lh-vv-top");
			root.classList.remove("lh-kb-open", "lh-phone");
		};
	}, [active]);
}

function isEditable(el: Element | null): boolean {
	if (!el) return false;
	if (el instanceof HTMLTextAreaElement) return true;
	if (el instanceof HTMLInputElement) return !["button", "checkbox", "radio", "range", "submit", "file"].includes(el.type);
	return (el as HTMLElement).isContentEditable === true;
}

/**
 * THE ANDROID BACK BRIDGE (fork: mobile-layout). The Android shell calls
 * `window.__lhBack()` on the system Back gesture before it touches WebView
 * history: true means the page closed something, so Back stops there.
 *
 * Back is one Esc on the topmost layer (the ONE-ESC LAW): every surfaced
 * layer (sheets, settings, menus, popovers, dialogs) already claims Esc at
 * the DOCUMENT level, so a synthetic Esc dispatched there closes exactly the
 * top one. It counts as handled when a layer prevented its default or
 * stopped it before the window. The window-level fallback chain (dictation,
 * then abort the turn) skips back-flagged events (isShellBack), so Back
 * never aborts a turn. Field-level handlers never see it (the target is
 * the document, not the focused field).
 */
export function isShellBack(e: Event): boolean {
	return (e as Event & { lhShellBack?: boolean }).lhShellBack === true;
}

function shellBack(): boolean {
	let reachedWindow = false;
	const mark = (e: KeyboardEvent) => {
		if (isShellBack(e)) reachedWindow = true;
	};
	window.addEventListener("keydown", mark);
	const esc = new KeyboardEvent("keydown", { key: "Escape", code: "Escape", bubbles: true, cancelable: true });
	Object.defineProperty(esc, "lhShellBack", { value: true });
	try {
		document.dispatchEvent(esc);
	} finally {
		window.removeEventListener("keydown", mark);
	}
	return esc.defaultPrevented || !reachedWindow;
}

/** Installs window.__lhBack for the Android shell (inert elsewhere). */
export function useShellBackBridge(): void {
	useEffect(() => {
		const w = window as Window & { __lhBack?: () => boolean };
		w.__lhBack = shellBack;
		return () => {
			if (w.__lhBack === shellBack) delete w.__lhBack;
		};
	}, []);
}
