/** GET /api/android/latest — the published Android shell version (see
 * src/server/android-releases.ts). 404 when updates aren't configured or
 * nothing is published yet. */
import { androidReleasesDir, readAndroidRelease } from "@/src/server";

export const dynamic = "force-dynamic";

export async function GET() {
	const dir = androidReleasesDir();
	const release = dir ? readAndroidRelease(dir) : null;
	if (!release) return Response.json({ error: "No Android release published" }, { status: 404, headers: { "Cache-Control": "no-store" } });
	return Response.json(release, { headers: { "Cache-Control": "no-store" } });
}
