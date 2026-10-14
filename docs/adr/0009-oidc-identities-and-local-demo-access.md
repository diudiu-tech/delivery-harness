# 0009. Use verified OIDC identities for pilot API access

- Status: Proposed
- Date: 2026-10-14
- Deciders: maintainers

## Context

Anonymous demo access previously granted every role. Static shared pilot
credentials cannot establish an independently authenticated reviewer or
bind tenant, city and station scope to a verified organization identity.

## Decision

Use a stateless OAuth2 resource server in pilot. Verify RS256 signatures,
issuer, audience, expiry, not-before and required subject/tenant/role/scope
claims. Organization administrators own the issuer, claims and key lifecycle.
Reject synthetic identities and static demo credentials in pilot.

Keep five explicitly selected synthetic bearer identities for local demo,
with small role sets and literal Host/Origin allowlists. An empty configured
list disables public demo tokens. Browser clients keep bearer credentials
only in memory; API authentication uses no cookies or sessions. Keep CSRF
protection enabled, with an exception only for explicit bearer requests.

## Consequences

Anonymous API clients now receive 401. Valid pilot clients use v2; v1 returns
410. Proxy and browser origin configuration must match explicit allowlists.
Authenticated authorization refusals require durable audit, while anonymous
failures use bounded throttling rather than unbounded database writes.
Signed local JWT fixtures prove validation behavior, not an organization's
IdP onboarding, revocation, key rotation or permission approval.

## Rejected alternatives

- Give anonymous demo users all roles: this defeats independent review and
  makes browser-origin requests authoritative without a chosen identity.
- Use a shared pilot token: it cannot establish individual accountability
  and couples identity lifecycle to deployment secrets.
- Accept client-provided role or tenant headers: they are unverified claims.

## Verification

Actual demo-profile tests exercise all five identities, missing credentials
and disabled defaults. Pilot tests use locally signed JWTs and check bad
signatures, claims, issuer, audience and expiry. Browser-boundary tests check
Host/Origin and explicit bearer handling. No live IdP is contacted.
