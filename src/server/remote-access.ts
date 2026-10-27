import { timingSafeEqual } from "node:crypto";

/** Remote (self-hosted) access gate. Off unless LH_ACCESS_TOKEN is set; the
 * desktop and plain dev modes are unchanged. When set, every request must
 * carry the token as the `lh_token` cookie (what the Android shell sets) or
 * an `Authorization: Bearer` header. A browser can bootstrap the cookie once
 * with `?lh_token=<token>` on any page URL. */
export const ACCESS_COOKIE = "lh_token";
export const ACCESS_QUERY = "lh_token";

export function accessToken(env: Record<string, string | undefined> = process.env): string | null {
	const token = env.LH_ACCESS_TOKEN;
	return token ? token : null;
}

export function tokenMatches(supplied: string | null | undefined, expected: string): boolean {
	if (!supplied) return false;
	const actual = Buffer.from(supplied);
	const wanted = Buffer.from(expected);
	return actual.length === wanted.length && timingSafeEqual(actual, wanted);
}

export function remoteAccessAuthorized(
	headers: Headers,
	cookie: string | undefined,
	env: Record<string, string | undefined> = process.env,
): boolean {
	const expected = accessToken(env);
	if (!expected) return true;
	if (expected.length < 32) return false;
	if (tokenMatches(cookie, expected)) return true;
	const auth = headers.get("authorization");
	const bearer = auth?.startsWith("Bearer ") ? auth.slice("Bearer ".length) : null;
	return tokenMatches(bearer, expected);
}
