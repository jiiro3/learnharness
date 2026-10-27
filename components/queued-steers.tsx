/**
 * THE QUEUED-STEER INDICATOR (owner spec 2026-10-03): steers sent but not
 * yet drained render as quiet one-liners ABOVE the composer shell —
 * right-aligned to where the shell's corner rounding starts, truncated
 * with an ellipsis, no background, no container, roughly half opacity.
 * Visible but very quiet; layout tunable later.
 *
 * A line dies the moment its card lands in the transcript: the drain's
 * verbatim message events upsert the real user card by the client-minted
 * id, and visibleQueuedSteers (session-core, pure) filters the queue
 * against the message ids. A steer parked server-side (stranded behind a
 * question) keeps its line — an honest "waiting for the next run".
 */
export interface QueuedSteerLine {
	id: string;
	text: string;
}

/** The pure body (SSR-pinnable): one right-aligned truncated line per
 *  queued steer, stacked.
 *
 *  `labelled` (phone, fork: mobile-layout): the quiet line is easy to miss
 *  on a small screen, where the composer simply empties — each steer
 *  renders as a small labelled row instead ("Queued · sends after this
 *  reply" over the text), so the learner knows it was kept. */
export function QueuedSteersBody({ steers, labelled = false }: { steers: QueuedSteerLine[]; labelled?: boolean }) {
	if (steers.length === 0) return null;
	if (labelled) {
		return (
			<div className="mb-2 flex flex-col gap-1" data-testid="queued-steers" role="status">
				{steers.map((s) => (
					<div key={s.id} className="rounded-xl border border-border/70 bg-card/80 px-3 py-1.5">
						<span className="block font-ui text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
							Queued · sends after this reply
						</span>
						<span title={s.text} className="block truncate text-sm">
							{s.text}
						</span>
					</div>
				))}
			</div>
		);
	}
	return (
		<div className="flex flex-col items-end gap-0.5 pb-1 pr-4" data-testid="queued-steers">
			{steers.map((s) => (
				<span
					key={s.id}
					title={s.text}
					className="block max-w-[240px] truncate font-ui text-[11px] text-muted-foreground/60"
				>
					{s.text}
				</span>
			))}
		</div>
	);
}
