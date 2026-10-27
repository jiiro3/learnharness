"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { DumbbellIcon, MoreVerticalIcon, PlusIcon, SearchIcon } from "lucide-react";
import { ActivitySection } from "@/components/activity-dock";
import { CoverageMenuBlock } from "@/components/extraction-coverage";
import { DeleteSessionDialog } from "@/components/delete-session-dialog";
import { BottomSheet } from "@/components/bottom-sheet";
import {
	exportChatMarkdown,
	renameChat,
	RenameRow,
	SessionRow,
	useChatList,
	type SessionKindFilter,
} from "@/components/sidebar";
import { fuzzyScore, type ChatListItem } from "@/src/core";
import { cn } from "@/lib/utils";

const KIND_FILTER_KEY = "lh:session-kind-filter";

/**
 * THE PHONE SESSIONS TAB (fork: mobile-layout): the sidebar's session
 * list as a full-screen page — the same snapshot (useChatList), the same
 * rows, the same fuzzy search and kind filter (shared localStorage key),
 * the same two creation doors. Right-click has no phone equivalent, so
 * each row carries a 44px "more" button (long-press also works where the
 * WebView fires contextmenu) opening an action sheet with the sidebar
 * menu's exact contents: Rename, Export, Delete, extraction coverage.
 * The background-agents section sits at the bottom, as in the sidebar.
 */
export function SessionsScreen() {
	const router = useRouter();
	const { chats, setChats, refresh } = useChatList();
	const [query, setQuery] = useState("");
	const [kindFilter, setKindFilter] = useState<SessionKindFilter>("all");
	const [menuFor, setMenuFor] = useState<ChatListItem | null>(null);
	const [renamingId, setRenamingId] = useState<string | null>(null);
	const [deleting, setDeleting] = useState<ChatListItem | null>(null);

	useEffect(() => {
		void refresh();
		try {
			const stored = window.localStorage.getItem(KIND_FILTER_KEY);
			if (stored === "teach" || stored === "all" || stored === "practice") setKindFilter(stored);
		} catch {
			/* storage unavailable: default filter */
		}
	}, [refresh]);
	const setFilter = useCallback((f: SessionKindFilter) => {
		setKindFilter(f);
		try {
			window.localStorage.setItem(KIND_FILTER_KEY, f);
		} catch {
			/* storage unavailable: the choice lasts this visit */
		}
	}, []);

	const shown = useMemo(() => {
		const q = query.trim();
		let list = chats;
		if (list && kindFilter !== "all") list = list.filter((c) => (c.kind ?? "teach") === kindFilter);
		if (!list || !q) return list;
		return list
			.map((c) => ({ c, score: fuzzyScore(q, c.title) }))
			.filter((r): r is { c: ChatListItem; score: number } => r.score !== null)
			.sort((a, b) => b.score - a.score)
			.map((r) => r.c);
	}, [chats, query, kindFilter]);

	return (
		<div className="flex h-dvh flex-col phone:h-full" data-testid="sessions-screen">
			<header className="flex h-14 shrink-0 items-center gap-2 border-b border-border px-4">
				<h1 className="font-title text-xl tracking-tight">Sessions</h1>
				<div className="ml-auto flex gap-1.5">
					<button
						type="button"
						data-testid="new-session"
						onClick={() => router.push("/chat/new")}
						className="flex h-11 items-center gap-1.5 rounded-md border border-border/60 px-3 text-xs uppercase tracking-wider text-muted-foreground active:bg-accent"
					>
						<PlusIcon className="size-3.5" />
						Teaching
					</button>
					<button
						type="button"
						data-testid="new-session-practice"
						onClick={() => router.push("/chat/new?mode=practice")}
						className="flex h-11 items-center gap-1.5 rounded-md border border-border/60 px-3 text-xs uppercase tracking-wider text-muted-foreground active:bg-accent"
					>
						<DumbbellIcon className="size-3.5" />
						Practice
					</button>
				</div>
			</header>

			<div className="flex shrink-0 flex-col gap-2 px-3 pt-3">
				<label className="relative block">
					<SearchIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground/70" />
					<input
						type="search"
						data-testid="session-search"
						value={query}
						onChange={(e) => setQuery(e.target.value)}
						placeholder="Search sessions…"
						aria-label="Search sessions"
						className="lh-input h-11 w-full rounded-md border border-border bg-background pl-9 pr-3 placeholder:text-muted-foreground/60"
					/>
				</label>
				<div className="flex items-center gap-0.5 rounded-md border border-border/60 bg-background p-0.5" data-testid="session-kind-filter">
					{(["teach", "practice", "all"] as const).map((f) => (
						<button
							key={f}
							type="button"
							data-testid={`kind-filter-${f}`}
							onClick={() => setFilter(f)}
							className={cn(
								"h-10 flex-1 rounded-sm px-2 text-[11px] uppercase tracking-wider transition-colors",
								kindFilter === f ? "bg-accent text-foreground" : "text-muted-foreground",
							)}
						>
							{f === "teach" ? "Teaching" : f === "all" ? "All" : "Practice"}
						</button>
					))}
				</div>
			</div>

			<div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-2">
				{shown === null ? (
					<div className="px-2 py-4 text-xs text-muted-foreground">Loading…</div>
				) : shown.length === 0 ? (
					<div className="px-2 py-4 text-xs text-muted-foreground">
						{query.trim() ? "No sessions match." : "No sessions yet."}
					</div>
				) : (
					shown.map((c) =>
						renamingId === c.id ? (
							<RenameRow
								key={c.id}
								chat={c}
								onCommit={(next) => {
									setRenamingId(null);
									void renameChat(c.id, next);
								}}
								onCancel={() => setRenamingId(null)}
							/>
						) : (
							<div key={c.id} className="flex items-center">
								<div className="min-w-0 flex-1">
									<SessionRow
										chat={c}
										active={false}
										onOpen={() => router.push(`/chat/${c.id}`)}
										onContextMenu={(e) => {
											e.preventDefault();
											setMenuFor(c);
										}}
									/>
								</div>
								<button
									type="button"
									aria-label={`Actions for ${c.title}`}
									data-testid="session-row-more"
									onClick={() => setMenuFor(c)}
									className="mb-0.5 flex size-11 shrink-0 items-center justify-center rounded-lg text-muted-foreground active:bg-accent"
								>
									<MoreVerticalIcon className="size-4" />
								</button>
							</div>
						),
					)
				)}
			</div>

			<div className="shrink-0">
				<ActivitySection />
			</div>

			<BottomSheet open={menuFor !== null} onClose={() => setMenuFor(null)} label="Session actions" testid="session-actions-sheet">
				{menuFor && (
					<div className="flex flex-col gap-0.5 px-2 pb-2 text-sm">
						<div className="truncate px-3 pb-2 font-prose text-(length:--lh-prose-size) text-foreground">{menuFor.title}</div>
						<SheetAction
							testid="ctx-rename"
							onClick={() => {
								setRenamingId(menuFor.id);
								setMenuFor(null);
							}}
						>
							Rename
						</SheetAction>
						<SheetAction
							testid="ctx-export-md"
							onClick={() => {
								exportChatMarkdown(menuFor.id);
								setMenuFor(null);
							}}
						>
							Export as Markdown…
						</SheetAction>
						<SheetAction
							testid="ctx-delete"
							muted
							onClick={() => {
								setDeleting(menuFor);
								setMenuFor(null);
							}}
						>
							Delete…
						</SheetAction>
						<div className="my-1 border-t border-border/60" aria-hidden="true" />
						<div className="px-1 text-xs">
							<CoverageMenuBlock chatId={menuFor.id} />
						</div>
					</div>
				)}
			</BottomSheet>

			<DeleteSessionDialog
				chat={deleting}
				onClose={() => setDeleting(null)}
				onDeleted={(id, _mode, graphRevision) => {
					setDeleting(null);
					setChats((prev) => (prev ? prev.filter((c) => c.id !== id) : prev));
					window.dispatchEvent(new CustomEvent("lh:graph-revision", { detail: { revision: graphRevision } }));
				}}
			/>
		</div>
	);
}

function SheetAction({
	children,
	onClick,
	testid,
	muted = false,
}: {
	children: React.ReactNode;
	onClick: () => void;
	testid: string;
	muted?: boolean;
}) {
	return (
		<button
			type="button"
			data-testid={testid}
			onClick={onClick}
			className={cn(
				"flex min-h-12 items-center rounded-lg px-3 text-left active:bg-accent",
				muted ? "text-muted-foreground" : "text-foreground",
			)}
		>
			{children}
		</button>
	);
}
