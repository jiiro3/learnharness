import { NextResponse, type NextRequest } from "next/server";
import { desktopAPIAuthorized } from "./src/server/desktop-access.ts";
import { ACCESS_COOKIE, ACCESS_QUERY, accessToken, remoteAccessAuthorized, tokenMatches } from "./src/server/remote-access.ts";

const NO_STORE = { "Cache-Control": "no-store" };

/** Applies to every API method, including SSE and spending/settings routes.
 * No public callback or worker bypass: internal jobs call server functions,
 * and any legitimate self-HTTP request must carry the private capability.
 * With LH_ACCESS_TOKEN set (self-hosted remote access) pages are gated too. */
export function proxy(request: NextRequest) {
	const isAPI = request.nextUrl.pathname.startsWith("/api/");
	const expected = accessToken();
	if (expected) {
		const queryToken = request.nextUrl.searchParams.get(ACCESS_QUERY);
		if (!isAPI && expected.length >= 32 && tokenMatches(queryToken, expected)) {
			const clean = request.nextUrl.clone();
			clean.searchParams.delete(ACCESS_QUERY);
			const res = NextResponse.redirect(clean, { headers: NO_STORE });
			res.cookies.set(ACCESS_COOKIE, expected, {
				httpOnly: true,
				sameSite: "lax",
				secure: request.nextUrl.protocol === "https:",
				path: "/",
				maxAge: 60 * 60 * 24 * 365,
			});
			return res;
		}
		if (!remoteAccessAuthorized(request.headers, request.cookies.get(ACCESS_COOKIE)?.value)) {
			return isAPI
				? NextResponse.json({ error: "LearnHarness access token required." }, { status: 401, headers: NO_STORE })
				: new NextResponse("LearnHarness is locked. Open it with ?lh_token=<access token>.", { status: 401, headers: NO_STORE });
		}
	}
	if (isAPI && !desktopAPIAuthorized(request.headers)) {
		return NextResponse.json(
			{ error: "Desktop API access requires the trusted LearnHarness window." },
			{ status: 403, headers: NO_STORE },
		);
	}
	return NextResponse.next();
}

export const config = { matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"] };
