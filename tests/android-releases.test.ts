import { test } from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { androidReleasesDir, readAndroidRelease } from "../src/server/android-releases.ts";

test("android releases are off without LH_ANDROID_RELEASES_DIR", () => {
	assert.equal(androidReleasesDir({}), null);
	assert.equal(androidReleasesDir({ LH_ANDROID_RELEASES_DIR: "/data/android" }), "/data/android");
});

test("readAndroidRelease accepts a well-formed manifest and rejects missing or malformed ones", () => {
	const dir = fs.mkdtempSync(path.join(os.tmpdir(), "lh-android-"));
	assert.equal(readAndroidRelease(dir), null);
	const good = { versionCode: 2, versionName: "0.1.1", sha256: "a".repeat(64), size: 123 };
	fs.writeFileSync(path.join(dir, "latest.json"), JSON.stringify(good));
	assert.deepEqual(readAndroidRelease(dir), good);
	for (const bad of [{ ...good, versionCode: "2" }, { ...good, sha256: "xyz" }, { ...good, size: 1.5 }, null]) {
		fs.writeFileSync(path.join(dir, "latest.json"), JSON.stringify(bad));
		assert.equal(readAndroidRelease(dir), null, JSON.stringify(bad));
	}
	fs.writeFileSync(path.join(dir, "latest.json"), "{not json");
	assert.equal(readAndroidRelease(dir), null);
});
