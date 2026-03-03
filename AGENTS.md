# Codex agent rules for rppg-java

## Scope
- Keep this repository as an MVP skeleton.
- Prefer minimal, explicit implementations over framework-heavy solutions.

## Technical constraints
- Java 25 + Gradle.
- Avoid introducing extra frameworks/modules unless explicitly requested.
- Keep camera/video dependencies out of unit tests.

## Testing
- Unit tests must be deterministic and hardware-independent.
- Use synthetic signals for signal-processing validation.
- Primary verification command: `./gradlew test`.

## File hygiene
- Keep changes small and targeted.
- Update README when build/runtime assumptions change.
- Respect `.aiignore` exclusions for generated and media artifacts.

## Communication
- After each fully completed iteration, always include a ready-to-use commit message as the last line of the response.
