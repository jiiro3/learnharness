"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { cn } from "@/lib/utils";

/**
 * The phone bottom sheet (fork: mobile-layout): a body-portaled panel
 * that rises from the bottom edge over a dim backdrop. Dismissal: tap the
 * backdrop, Esc (claimed at the document, the one-Esc law), or drag the
 * grab handle down past a threshold. Pads by the safe-area bottom inset.
 *
 * `panelClassName` sizes the panel (content-height by default; the plan
 * sheet passes a fixed height for its pan/zoom viewport).
 */
export function BottomSheet({
	open,
	onClose,
	label,
	testid,
	panelClassName,
	header,
	children,
}: {
	open: boolean;
	onClose: () => void;
	label: string;
	testid?: string;
	panelClassName?: string;
	/** Optional row beside the grab handle (title, actions). */
	header?: React.ReactNode;
	children: React.ReactNode;
}) {
	const [dragY, setDragY] = useState(0);
	const dragRef = useRef<{ y0: number; id: number } | null>(null);

	useEffect(() => {
		if (!open) return;
		const onKey = (e: KeyboardEvent) => {
			if (e.key === "Escape") {
				e.preventDefault();
				e.stopPropagation();
				onClose();
			}
		};
		document.addEventListener("keydown", onKey);
		return () => document.removeEventListener("keydown", onKey);
	}, [open, onClose]);

	useEffect(() => {
		if (!open) setDragY(0);
	}, [open]);

	if (!open || typeof document === "undefined") return null;

	const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
		if ((e.target as HTMLElement).closest("button")) return;
		dragRef.current = { y0: e.clientY, id: e.pointerId };
		e.currentTarget.setPointerCapture(e.pointerId);
	};
	const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
		const d = dragRef.current;
		if (!d || d.id !== e.pointerId) return;
		setDragY(Math.max(0, e.clientY - d.y0));
	};
	const onPointerEnd = (e: React.PointerEvent<HTMLDivElement>) => {
		const d = dragRef.current;
		if (!d || d.id !== e.pointerId) return;
		dragRef.current = null;
		if (e.clientY - d.y0 > 80) onClose();
		else setDragY(0);
	};

	return createPortal(
		<div
			className="fixed inset-0 z-50 flex flex-col justify-end bg-background/70"
			data-testid={testid ? `${testid}-backdrop` : undefined}
			onClick={(e) => {
				if (e.target === e.currentTarget) onClose();
			}}
		>
			<div
				role="dialog"
				aria-modal="true"
				aria-label={label}
				data-testid={testid}
				className={cn(
					"lh-sheet-in flex max-h-[calc(var(--lh-vv-h)-var(--lh-safe-top)-1rem)] flex-col rounded-t-2xl border-t border-border bg-card pb-(--lh-safe-bottom) shadow-2xl",
					panelClassName,
				)}
				style={{
					transform: dragY ? `translateY(${dragY}px)` : undefined,
					transition: dragRef.current ? "none" : "transform 160ms ease-out",
				}}
			>
				<div
					className="flex shrink-0 touch-none flex-col"
					onPointerDown={onPointerDown}
					onPointerMove={onPointerMove}
					onPointerUp={onPointerEnd}
					onPointerCancel={onPointerEnd}
				>
					<div className="flex justify-center pt-2 pb-1.5" aria-hidden="true">
						<div className="h-1 w-10 rounded-full bg-muted-foreground/40" />
					</div>
					{header}
				</div>
				<div className="flex min-h-0 flex-1 flex-col">{children}</div>
			</div>
		</div>,
		document.body,
	);
}
