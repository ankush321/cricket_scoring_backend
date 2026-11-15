# MCP (Model Context Protocol)

**Not applicable.** This repository has no Model Context Protocol server or client:

- no MCP SDK dependency in `build.gradle.kts`
- no MCP tools, resources or prompts defined in the source
- no stdio, SSE or streamable-HTTP MCP transport

The server exposes only the REST endpoints in `API.md`.

## If you add MCP later

These would be natural places to plug in:

- **Tools** would wrap service-layer calls (for example, future match or scoring services), not the route handlers. That way they reuse the same validation and `AuthException` mapping.
- **Auth** should reuse the `jwt("access")` provider (or `JwtService.verifier()` plus `SessionService.isActive`) and scope every tool call to the resulting `AccessPrincipal.userId`, through `owner_user_id` and `memberships`.
- **Resources** could expose read-only views such as a match's `match_events` log, ordered by `sequence`.

Document each tool's name, input schema, output and side effects here once they exist.
