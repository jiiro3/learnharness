/** GET /api/android/apk — the published Android shell APK bytes. The shell
 * verifies size + sha256 from /api/android/latest before installing. */
import * as fs from "node:fs";
import { Readable } from "node:stream";
import { androidApkPath, androidReleasesDir } from "@/src/server";

export const dynamic = "force-dynamic";

export async function GET() {
	const dir = androidReleasesDir();
	if (!dir) return Response.json({ error: "No Android release published" }, { status: 404 });
	const file = androidApkPath(dir);
	let size: number;
	try {
		size = fs.statSync(file).size;
	} catch {
		return Response.json({ error: "No Android release published" }, { status: 404 });
	}
	const body = Readable.toWeb(fs.createReadStream(file)) as ReadableStream<Uint8Array>;
	return new Response(body, {
		headers: {
			"Content-Type": "application/vnd.android.package-archive",
			"Content-Length": String(size),
			"Content-Disposition": 'attachment; filename="learnharness.apk"',
			"Cache-Control": "no-store",
		},
	});
}
