import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { compactModelLabel } from "../components/usage-format.ts";

/** The phone model pill's one-line label (fork: mobile-layout). */
describe("compactModelLabel", () => {
	it("drops the provider prefix, the ~ alias marker and a -latest suffix", () => {
		assert.equal(compactModelLabel("~anthropic/claude-opus-latest"), "claude-opus");
		assert.equal(compactModelLabel("~deepseek/deepseek-flash-latest"), "deepseek-flash");
		assert.equal(compactModelLabel("claude-opus-latest"), "claude-opus");
	});
	it("keeps everything else as the provider writes it", () => {
		assert.equal(compactModelLabel("anthropic/claude-sonnet-4.5:thinking"), "claude-sonnet-4.5:thinking");
		assert.equal(compactModelLabel("deepseek/deepseek-v4.1-flash"), "deepseek-v4.1-flash");
		assert.equal(compactModelLabel("latest"), "latest");
		// A provider-qualified stamp still drops its provider.
		assert.equal(compactModelLabel("openrouter:anthropic/claude-sonnet-4.5:thinking"), "claude-sonnet-4.5:thinking");
	});
});
