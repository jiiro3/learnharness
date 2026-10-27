import { SessionsScreen } from "@/components/sessions-screen";

/**
 * The phone Sessions tab (fork: mobile-layout): the sidebar's session list
 * as its own page, reached from the bottom tabs. On a desktop-width window
 * it renders the same list in the main column (the sidebar stays the
 * primary entry there).
 */
export default function SessionsPage() {
	return <SessionsScreen />;
}
