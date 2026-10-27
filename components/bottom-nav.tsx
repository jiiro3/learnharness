"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ArrowLeftIcon, ListIcon, SettingsIcon, WaypointsIcon } from "lucide-react";
import { useSettingsDialog } from "@/components/settings/settings-dialog.tsx";
import { cn } from "@/lib/utils";

/**
 * THE PHONE TABS (fork: mobile-layout): the bottom navigation that
 * replaces the sidebar on a phone. Graph is home (the app's own
 * hierarchy: the graph is the home page, the session is the classroom);
 * Sessions is the full-screen list that the sidebar carried; Settings
 * opens the same dialog the sidebar's row does (full-screen on a phone).
 *
 * CSS-gated (`hidden phone:flex`) so the desktop never renders it and the
 * phone never flashes without it. Hidden inside a session (/chat/*: the
 * session is full-screen, its top bar carries the back button) and while
 * the soft keyboard is up (.lh-kb-open — the composer needs the room).
 */
export function BottomNav() {
	const pathname = usePathname();
	const { open: openSettings } = useSettingsDialog();
	useTrackInAppNavigation(pathname);
	if (pathname.startsWith("/chat")) return null;
	const tab = pathname === "/sessions" ? "sessions" : pathname === "/" ? "graph" : null;
	return (
		<nav
			aria-label="Main"
			data-testid="bottom-nav"
			className="hidden shrink-0 border-t border-border bg-background pb-(--lh-safe-bottom) phone:flex [.lh-kb-open_&]:hidden"
		>
			<NavTab href="/" label="Graph" active={tab === "graph"} icon={<WaypointsIcon className="size-5" />} />
			<NavTab href="/sessions" label="Sessions" active={tab === "sessions"} icon={<ListIcon className="size-5" />} />
			<button
				type="button"
				data-testid="bottom-nav-settings"
				onClick={() => openSettings()}
				className="flex min-h-14 flex-1 flex-col items-center justify-center gap-1 text-[11px] uppercase tracking-wider text-muted-foreground active:bg-accent"
			>
				<SettingsIcon className="size-5" />
				Settings
			</button>
		</nav>
	);
}

function NavTab({ href, label, active, icon }: { href: string; label: string; active: boolean; icon: React.ReactNode }) {
	return (
		<Link
			href={href}
			aria-current={active ? "page" : undefined}
			data-testid={`bottom-nav-${label.toLowerCase()}`}
			className={cn(
				"flex min-h-14 flex-1 flex-col items-center justify-center gap-1 text-[11px] uppercase tracking-wider active:bg-accent",
				active ? "text-foreground" : "text-muted-foreground",
			)}
		>
			{icon}
			{label}
		</Link>
	);
}

/* In-app navigation depth: the phone back button pops history only when
 * the previous entry is ours (a session opened from the list or a graph
 * card); a cold deep link into a session goes to the list instead of
 * leaving the app. Module scope: one shell per document. */
let inAppNavigations = -1;
let lastPath: string | null = null;
function useTrackInAppNavigation(pathname: string) {
	useEffect(() => {
		if (pathname === lastPath) return;
		lastPath = pathname;
		inAppNavigations += 1;
	}, [pathname]);
}

/**
 * The phone top bar's leading control (fork: mobile-layout): back out of
 * a full-screen session or draft. CSS-gated — renders nothing visible off
 * phone, where the sidebar toggle owns that slot.
 */
export function PhoneBackButton({ fallback = "/sessions" }: { fallback?: string }) {
	// A Link (not useRouter): the control also renders in server-side
	// render tests with no app router mounted. In-app history pops instead.
	return (
		<Link
			href={fallback}
			aria-label="Back"
			data-testid="phone-back"
			onClick={(e) => {
				if (inAppNavigations > 0) {
					e.preventDefault();
					window.history.back();
				}
			}}
			className="-ml-1 hidden size-11 shrink-0 items-center justify-center rounded-md text-muted-foreground active:bg-accent phone:flex"
		>
			<ArrowLeftIcon className="size-5" />
		</Link>
	);
}
