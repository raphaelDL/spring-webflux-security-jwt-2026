<!--
Alternative titles:
- I Modernized My 8-Year-Old, 300★ Spring Repo with Claude Code — and It Found a Security Bug I'd Been Shipping for Years
- What Happens When You Point an AI Agent at an 8-Year-Old Spring Project
-->

# From Spring Boot 2 to 4 with Claude Code — and the bug that was hiding the whole time

Eight years ago I published a little demo: **JWT authentication and authorization
with Spring WebFlux and Spring Security's reactive stack**. It was meant as a
teaching example — how to mint a JWT after an HTTP Basic login, then authenticate
API calls with that token, all reactive and stateless. To my surprise it picked up
**~300 stars and 86 forks**. People actually learned from it.

It also rotted. Spring Boot 2.1, Java 8, Spring Security 5, JUnit 4 — all from 2018.
So I decided to bring it into 2026 with **[Claude Code](https://claude.com/claude-code)**,
and I treated it as an experiment: how far can an AI coding agent take a real
modernization, not a toy?

## The modernization

I kept the original repo untouched and worked in a fresh one. Claude Code handled
the upgrade in staged, reviewable commits:

- **Spring Boot 2.1 → 4.0**, **Java 8 → 21**, **Spring Security 5 → 7**,
  **JUnit 4 → 5**, **Nimbus JOSE+JWT 6 → 10**, **Gradle 5 → 9.4**.
- Rewrote the security config from the old `.authorizeExchange()…and()` chain to the
  **Spring Security 7 lambda DSL** (the old chained style is *removed* in 7, not just
  deprecated).
- Kept **both Gradle and Maven** builds in sync.

A couple of things I'd have lost an afternoon to on my own, and Claude Code just knew:

- Spring Boot 4 split test support into **per-module starters**
  (`spring-boot-starter-webflux-test`, `spring-boot-starter-security-test`).
- `@AutoConfigureWebTestClient` **moved packages and is now required** even with
  `@SpringBootTest(RANDOM_PORT)` — without it the test fails with a confusing
  "no `WebTestClient` bean."
- The Nimbus 6→10 jump (four major versions!) needed **zero code changes** — the JWT
  classes only used stable core APIs.

Useful, but honestly the version bumps are the boring part. Here's the part that made
me sit up.

## The bug that had been there the whole time

My demo's title literally said "**Authentication and Authorization**." The controller
had role rules on every endpoint:

```java
@GetMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public Flux<FormattedMessage> admin() { ... }
```

The JWT carried a `roles` claim. Everything *looked* like role-based authorization.

It wasn't. While verifying behavior, Claude Code logged in as my demo user — who has
`USER` and `ADMIN` but **not** `GUEST` — and called the `GUEST`-only endpoint:

```
GET /api/guest  →  200 OK   ["Hello Guest!"]
```

That should have been a `403`. The reason: `@PreAuthorize` does nothing unless method
security is switched on, and I had **never added `@EnableReactiveMethodSecurity`**. So
for eight years, across 300 stars and 86 forks, the role annotations were **silently
ignored** — any valid token could reach any endpoint. Anyone who used my demo as a
starting point inherited that gap.

The fix was one annotation. The point is that it took *running the app and checking the
actual response* to find it — not reading the code, which looked perfectly correct.
That's the difference between an agent that edits text and one that verifies behavior.

## Closing the gap properly

Beyond the one-line fix, Claude Code:

- Added a **7-test suite** proving the flow end-to-end: login issues a JWT, a valid
  token with the right role gets `200`, a valid token *without* the role now gets
  `403`, and malformed / expired / untrusted-issuer tokens get `401`. Green on both
  Gradle and Maven.
- **Hardened the verifier** — it now checks the token's **issuer**, not just signature
  and expiry.
- Replaced `e.printStackTrace()` with real logging and tidied small code smells.
- Wrote an **`OVERVIEW.md`** explaining the architecture, with sequence diagrams — and
  honestly documented the demo's remaining shortcuts (in-source secret,
  `withDefaultPasswordEncoder`) instead of pretending it's production-ready.

## Takeaways

1. **Modernization is more than version numbers.** The most valuable change wasn't
   Spring Boot 4 — it was fixing authorization that never worked.
2. **"Looks correct" and "is correct" are different.** A latent bug survived years of
   human eyes because the code reads fine. It got caught by *executing* the thing.
3. **An agent that runs your app earns trust.** Staged commits, both build tools
   verified, the app booted and curled — I could actually review what changed and why.

If you want to see the result:

- **Modernized:** https://github.com/raphaelDL/spring-webflux-security-jwt-2026
- **Original (for posterity):** https://github.com/raphaelDL/spring-webflux-security-jwt

If you've got an old repo gathering dust, it might be hiding something more interesting
than outdated dependencies.
