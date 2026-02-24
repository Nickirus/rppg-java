# rppg-java (MVP skeleton)

Minimal Java/Gradle skeleton for rPPG signal processing.

## Stack
- Java 25 target (falls back to the highest local JDK when Java 25 is unavailable)
- Gradle
- JUnit 5 (tests only)

## Run tests
- `./gradlew test`

## Notes
- No camera access is required for tests.
- Signal-processing tests use synthetic sine signals.