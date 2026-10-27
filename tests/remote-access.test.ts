import { test } from "node:test";
import assert from "node:assert/strict";
import { NextRequest } from "next/server";
import { remoteAccessAuthorized } from "../src/server/remote-access.ts";
import { proxy } from "../proxy.ts";

const token = "t".repeat(48);
const origin = "https://learnharness.oxy.internal";

function withToken<T>(value: string | undefined, fn: () => T): T {
	const original = process.env.LH_ACCESS_TOKEN;
	if (value === undefined) delete process.env.LH_ACCESS_TOKEN; else process.env.LH_ACCESS_TOKEN = value;
	try {
		return fn();
	} finally {
		if (original === undefined) delete process.env.LH_ACCESS_TOKEN; else process.env.LH_ACCESS_TOKEN = original;
	}
}

test("remote access gate is off without LH_ACCESS_TOKEN and fails closed on short tokens", () => {
	assert(remoteAccessAuthorized(new Headers(), undefined, {}));
	assert(!remoteAccessAuthorized(new Headers(), "short", { LH_ACCESS_TOKEN: "short" }));
});

test("remote access accepts the lh_token cookie or a bearer header and nothing else", () => {
	const env = { LH_ACCESS_TOKEN: token };
	assert(remoteAccessAuthorized(new Headers(), token, env));
	assert(remoteAccessAuthorized(new Headers({ authorization: `Bearer ${token}` }), undefined, env));
	for (const cookie of [undefined, "", "wrong", token + "x"]) assert(!remoteAccessAuthorized(new Headers(), cookie, env));
	assert(!remoteAccessAuthorized(new Headers({ authorization: token }), undefined, env));
});

test("proxy locks pages and API without the token, and passes them with the cookie", () => {
	withToken(token, () => {
		assert.equal(proxy(new NextRequest(origin + "/api/settings")).status, 401);
		assert.equal(proxy(new NextRequest(origin + "/")).status, 401);
		const cookie = { headers: { cookie: `lh_token=${token}` } };
		assert.equal(proxy(new NextRequest(origin + "/api/settings", cookie)).headers.get("x-middleware-next"), "1");
		assert.equal(proxy(new NextRequest(origin + "/chat/new", cookie)).headers.get("x-middleware-next"), "1");
	});
});

test("?lh_token on a page sets an HttpOnly cookie and redirects to the clean URL; never on API routes", () => {
	withToken(token, () => {
		const res = proxy(new NextRequest(`${origin}/graph?x=1&lh_token=${token}`));
		assert.equal(res.status, 307);
		assert.equal(res.headers.get("location"), `${origin}/graph?x=1`);
		const setCookie = res.headers.get("set-cookie") ?? "";
		assert.match(setCookie, /lh_token=/);
		assert.match(setCookie, /HttpOnly/i);
		assert.match(setCookie, /Secure/i);
		assert.equal(proxy(new NextRequest(`${origin}/?lh_token=wrong`)).status, 401);
		assert.equal(proxy(new NextRequest(`${origin}/api/settings?lh_token=${token}`)).status, 401);
	});
});
