# Project Overview

A small, didactic demo showing how to secure a **reactive REST API** built with
**Spring WebFlux** using **JSON Web Tokens (JWT)** on top of **Spring Security's
reactive stack**. It is intentionally tiny so the moving parts of token-based
authentication are easy to follow.

> **Looking for the step-by-step tutorial?** The [README](README.md) walks
> through building this from scratch. **This document describes what the
> finished project does and how the pieces fit together.**

## The idea in one paragraph

Authentication happens in **two phases**. First, a client logs in once with
**HTTP Basic** credentials; on success the server mints a signed JWT and returns
it in the `Authorization` response header. From then on the client drops Basic
auth and sends that JWT as a **`Bearer` token** on every API call. Because the
token is signed and self-contained, the server can authenticate each request
**statelessly** — there is no server-side session, and the token itself carries
the user's identity and roles.

## Tech stack

| Concern        | Choice                                            |
|----------------|---------------------------------------------------|
| Language       | Java 21                                           |
| Framework      | Spring Boot 4.0.x (Spring Framework 7)            |
| Web            | Spring WebFlux (reactive, non-blocking, on Netty) |
| Security       | Spring Security 7 (reactive / `ServerHttpSecurity`) |
| JWT            | Nimbus JOSE + JWT (HMAC `HS256`)                  |
| Build          | Gradle **and** Maven (kept in sync)               |
| Tests          | JUnit 5 + `WebTestClient`                         |

## Endpoints

| Method & path   | Purpose                                                   | Access in this demo                      |
|-----------------|-----------------------------------------------------------|------------------------------------------|
| `GET /`         | Welcome message                                           | Requires authentication (HTTP Basic)     |
| `GET /login`    | Same message, but the response carries a freshly minted JWT in the `Authorization` header | Requires authentication (HTTP Basic) |
| `GET /api/private` | `"Hello User!"`                                        | Valid `Bearer` JWT **and** `ROLE_USER`   |
| `GET /api/admin`   | `"Hello Admin!"`                                       | Valid `Bearer` JWT **and** `ROLE_ADMIN`  |
| `GET /api/guest`   | `"Hello Guest!"`                                       | Valid `Bearer` JWT **and** `ROLE_GUEST`  |

All endpoints return a `FormattedMessage` (`{ "name": ..., "message": ... }`)
emitted as a reactive `Flux`.

The single in-memory user is **`user` / `user`**, holding the roles `USER` and
`ADMIN` — so it can reach `/api/private` and `/api/admin`, but gets `403` on
`/api/guest`, which is a quick way to see role-based authorization at work.

## How a request flows

### Phase 1 — log in with Basic auth, receive a JWT

```mermaid
sequenceDiagram
    participant C as Client
    participant F as Basic auth filter
    participant H as BasicAuthenticationSuccessHandler
    participant Ctl as MessageController

    C->>F: GET /login  (Authorization: Basic user:user)
    F->>F: Validate credentials against the in-memory user store
    F->>H: Authentication success
    H->>H: JWTTokenService.generateToken(...) — sign claims with HS256
    H-->>C: Response + header  Authorization: Bearer <jwt>
    F->>Ctl: continue filter chain → welcome message body
```

### Phase 2 — call the API with the Bearer token

```mermaid
sequenceDiagram
    participant C as Client
    participant F as Bearer auth filter
    participant Cv as ServerHttpBearerAuthenticationConverter
    participant V as JWTCustomVerifier
    participant Ctl as MessageController

    C->>F: GET /api/admin  (Authorization: Bearer <jwt>)
    F->>Cv: Extract token from the Authorization header
    Cv->>V: Verify signature, expiry, and issuer
    alt token valid
        V-->>Cv: SignedJWT
        Cv->>Cv: Build Authentication from the "roles" claim
        Cv-->>F: Authentication
        F->>Ctl: forward request (now authenticated)
        Ctl->>Ctl: @PreAuthorize checks the required role
        Ctl-->>C: 200 with body, or 403 if the role is missing
    else token missing / malformed / expired / wrong issuer / bad signature
        V-->>Cv: empty
        Cv-->>F: no Authentication
        F-->>C: HTTP 401 Unauthorized
    end
```

## The security filter chain

Configured in `SecuredRestApplication.springSecurityFilterChain(...)` using the
Spring Security lambda DSL:

- **Authorization rules:** `/login`, `/`, and `/api/**` all require an
  authenticated exchange. Paths not listed are open.
- **Two custom `AuthenticationWebFilter`s** are added to the chain:
  - a **Basic auth filter** (order `HTTP_BASIC`) that authenticates against the
    in-memory user store and, on success, runs the custom success handler that
    issues the JWT;
  - a **Bearer token filter** (order `AUTHENTICATION`, scoped to `/api/**`) that
    validates the incoming JWT and builds the `Authentication` from its claims.

The filter that issues tokens and the filter that consumes them are deliberately
separate, which mirrors how a real login service and resource server divide the
work.

**Role-based authorization** is layered on top via `@EnableReactiveMethodSecurity`
(on `SecuredRestApplication`), which activates the `@PreAuthorize("hasRole(...)")`
rules on the controller. Reaching `/api/admin` therefore needs both a valid token
*and* `ROLE_ADMIN`; a valid token without the required role is rejected with `403`.

## What the JWT contains

Built and signed in `JWTTokenService` (`HS256`, shared secret):

- `sub` — the username
- `iss` — `rapha.io`
- `roles` — the user's authorities, comma-joined (e.g. `ROLE_USER,ROLE_ADMIN`)
- `exp` — 24 hours from issue

`JWTCustomVerifier` rejects a token unless it parses, is **unexpired**, comes from
the **trusted issuer** (`rapha.io`), and has a **valid signature** — otherwise it
yields an empty result and the request is rejected with `401`.

## Component map

```
io.rapha.spring.reactive.security
├── SecuredRestApplication            Main class; security filter chain + in-memory user
├── api/
│   └── MessageController             REST endpoints (/, /login, /api/*)
├── service/
│   └── MessageService                Produces the FormattedMessage Flux
├── domain/
│   └── FormattedMessage              Response DTO { name, message }
└── auth/
    ├── basic/
    │   └── BasicAuthenticationSuccessHandler   On Basic-auth success, attaches a JWT
    ├── bearer/
    │   ├── ServerHttpBearerAuthenticationConverter   Token → Authentication
    │   └── BearerTokenReactiveAuthenticationManager  Pass-through (token already verified)
    └── jwt/
        ├── JWTTokenService                  Builds + signs the JWT
        ├── JWTCustomSigner / JWTCustomVerifier   HMAC sign / verify
        ├── JWTSecrets                        The (demo) shared secret
        ├── AuthorizationHeaderPayload        Reads the Authorization header
        └── UsernamePasswordAuthenticationBearer  Verified claims → Authentication
```

> **Why a "pass-through" authentication manager?** With JWT, everything needed to
> authenticate already lives inside the (verified) token, so
> `BearerTokenReactiveAuthenticationManager` simply returns the `Authentication`
> the converter built. It exists only to satisfy `AuthenticationWebFilter`'s
> contract, which requires a non-null manager.

## Try it

Start the app (`./gradlew bootRun` or `mvn spring-boot:run`), then:

```shell
# 1. Log in with Basic auth and grab the issued token from the response header
curl -v -u user:user localhost:8080/login
#   ...look for:  Authorization: Bearer eyJhbGciOiJIUzI1Ni...

# 2. Call the API with that token
curl -v -H "Authorization: Bearer eyJhbGciOiJIUzI1Ni..." localhost:8080/api/admin
#   → 200  [{"name":"Admin","message":"Hello Admin!"}]

# 3. No token (or a bad one) is rejected
curl -i localhost:8080/api/admin            # → 401
```

## Scope & caveats — this is a learning demo, not production

Several shortcuts are intentional, to keep the example focused on the JWT flow:

- **Single hard-coded user** stored in memory via `MapReactiveUserDetailsService`.
- **A fixed, in-source HMAC secret** (`JWTSecrets.DEFAULT_SECRET`) and
  `User.withDefaultPasswordEncoder()` — both flagged by Spring as unsafe outside
  samples. In a real system the secret would come from configuration / a secret
  manager, and users from a real store.
- **No token refresh / revocation** and no HTTPS enforcement.

These are good starting points if you want to extend the demo toward something
production-shaped.
