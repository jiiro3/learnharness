import * as fs from "node:fs";
import * as path from "node:path";

/** Android shell self-update (self-hosted deployments). Off unless
 * LH_ANDROID_RELEASES_DIR is set. That directory holds `latest.json`
 * ({versionCode, versionName, sha256, size}) and `learnharness.apk`, written
 * by android/scripts/publish.sh (APK first, manifest last). Both routes sit
 * behind the proxy gate like every other API route. */
export type AndroidRelease = { versionCode: number; versionName: string; sha256: string; size: number };

export const ANDROID_APK_NAME = "learnharness.apk";

export function androidReleasesDir(env: Record<string, string | undefined> = process.env): string | null {
	return env.LH_ANDROID_RELEASES_DIR || null;
}

export function readAndroidRelease(dir: string): AndroidRelease | null {
	let raw: unknown;
	try {
		raw = JSON.parse(fs.readFileSync(path.join(dir, "latest.json"), "utf-8"));
	} catch {
		return null;
	}
	const r = raw as Partial<AndroidRelease> | null;
	if (
		!r ||
		!Number.isInteger(r.versionCode) ||
		typeof r.versionName !== "string" ||
		typeof r.sha256 !== "string" ||
		!/^[0-9a-f]{64}$/.test(r.sha256) ||
		!Number.isInteger(r.size)
	) {
		return null;
	}
	return { versionCode: r.versionCode!, versionName: r.versionName, sha256: r.sha256, size: r.size! };
}

export function androidApkPath(dir: string): string {
	return path.join(dir, ANDROID_APK_NAME);
}
