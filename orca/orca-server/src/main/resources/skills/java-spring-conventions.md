---
name: java-spring-conventions
description: Conventions for Java 17+/21 and Spring Boot services
---
# Java / Spring Boot

- Build with the project's wrapper (`./mvnw -q verify` or `./gradlew test`); keep the build green.
- Constructor injection only; no field injection. Keep controllers thin, logic in services.
- Use `record`s for DTOs, `Optional` for absent results, `java.time` for dates (store UTC, show local time).
- Money and quantities: `BigDecimal` with explicit scale and rounding, or integer base units — never `double`.
- Transactions on service methods (`@Transactional`); no remote calls inside a database transaction.
- Database changes only through migrations (Flyway/Liquibase); never edit an applied migration.
- Tests: JUnit 5, AssertJ or JUnit assertions; slice tests (`@WebMvcTest`, `@DataJpaTest`) before full contexts.
