"use client";
/**
 * The Advanced section (fork: mobile-layout, owner-approved): developer
 * knobs that used to crowd the graph view's display popover — the region
 * bitmap's rendering tuning (Dilation, Blur, Edge alpha, Bitmap
 * resolution). Same storage as before (the server's render settings,
 * /api/graph/render-settings) through the shared client copy
 * (graph-render-settings.ts), so a change here reaches an open graph
 * live, exactly as it did from the popover.
 *
 * The regions' on/off switch ("Background colors") stays in the graph
 * popover — it is a display choice, not tuning. While it is off these
 * rows dim and lock (the dependency graying convention), and say why.
 */

import {
	REGION_BITMAP_OPTIONS,
	REGION_BLUR_LADDER,
	REGION_DILATION_LADDER,
	REGION_EDGE_ALPHA_LADDER,
	fromLadderPosition,
	toLadderPosition,
	type GraphRenderSettings,
} from "@/src/core";
import { useRenderSettings, type RenderSettingsUpdate } from "@/components/graph-render-settings";
import { SettingsGroup, SettingsRow, ThinSelect } from "./controls.tsx";

type Regions = GraphRenderSettings["regions"];

/** The popover's value display: one decimal from 1 up, two below. */
function sizeDisplay(v: number): string {
	return `${v >= 1 ? Math.round(v * 10) / 10 : Math.round(v * 100) / 100}`;
}

/** A ladder slider (0-100, step 10 — the render-settings ladders: 11
 *  stops, the default dead-center) with its value beside it. */
function LadderSlider({
	label,
	value,
	ladder,
	disabled,
	onChange,
	testid,
}: {
	label: string;
	value: number;
	ladder: readonly number[];
	disabled: boolean;
	onChange: (v: number) => void;
	testid: string;
}) {
	return (
		<span className="flex items-center gap-2">
			<input
				type="range"
				min={0}
				max={100}
				step={10}
				value={toLadderPosition(value, ladder)}
				disabled={disabled}
				aria-label={label}
				data-testid={testid}
				onChange={(e) => onChange(fromLadderPosition(Number(e.target.value), ladder))}
				className="h-1.5 w-32 accent-primary disabled:cursor-not-allowed phone:h-11"
			/>
			<span className="w-8 text-right font-ui text-xs tabular-nums text-muted-foreground">{sizeDisplay(value)}</span>
		</span>
	);
}

/** The section body, given the settings + the change seam (pure — the
 *  SSR tests render it directly). */
export function AdvancedSectionBody({
	settings,
	changeSettings,
}: {
	settings: GraphRenderSettings;
	changeSettings: (update: RenderSettingsUpdate) => void;
}) {
	const regions = settings.regions;
	const off = !regions.enabled;
	const setRegions = (patch: Partial<Regions>) =>
		changeSettings((cur) => ({ ...cur, regions: { ...cur.regions, ...patch } }));
	return (
		<SettingsGroup label="Graph regions" testid="group-graph-regions">
			{off && (
				<p className="text-xs text-muted-foreground/70" data-testid="advanced-regions-off">
					Background colors are off in the graph&apos;s display settings.
				</p>
			)}
			<SettingsRow label="Dilation" dimmed={off} testid="advanced-row-dilation">
				<LadderSlider
					label="Dilation"
					value={regions.dilation}
					ladder={REGION_DILATION_LADDER}
					disabled={off}
					onChange={(dilation) => setRegions({ dilation })}
					testid="input-region-dilation"
				/>
			</SettingsRow>
			<SettingsRow label="Blur" dimmed={off} testid="advanced-row-blur">
				<LadderSlider
					label="Blur"
					value={regions.blur}
					ladder={REGION_BLUR_LADDER}
					disabled={off}
					onChange={(blur) => setRegions({ blur })}
					testid="input-region-blur"
				/>
			</SettingsRow>
			<SettingsRow label="Edge alpha" dimmed={off} testid="advanced-row-edge-alpha">
				<LadderSlider
					label="Edge alpha"
					value={regions.edgeAlpha}
					ladder={REGION_EDGE_ALPHA_LADDER}
					disabled={off}
					onChange={(edgeAlpha) => setRegions({ edgeAlpha })}
					testid="input-region-edge-alpha"
				/>
			</SettingsRow>
			<SettingsRow label="Bitmap resolution" dimmed={off} testid="advanced-row-bitmap">
				<ThinSelect
					value={String(regions.bitmap)}
					ariaLabel="bitmap resolution"
					testid="input-region-bitmap"
					disabled={off}
					onChange={(v) => setRegions({ bitmap: Number(v) })}
				>
					{REGION_BITMAP_OPTIONS.map((o) => (
						<option key={o} value={o}>
							{o}
						</option>
					))}
				</ThinSelect>
			</SettingsRow>
		</SettingsGroup>
	);
}

/** The mounted section: the shared render settings (loads on mount). */
export function AdvancedSection() {
	const [settings, changeSettings] = useRenderSettings();
	return <AdvancedSectionBody settings={settings} changeSettings={changeSettings} />;
}
