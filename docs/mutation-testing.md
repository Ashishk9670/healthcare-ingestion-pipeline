# Mutation testing (PIT)

PIT mutates the PHI-critical packages and checks that unit tests notice. Scope: `parse`, `logging` (masking),
`deid`, `consent`, `crypto`. It is optional and slow, so it runs only via the `Mutation testing` workflow
(`workflow_dispatch` and weekly), never on push or pull request.

Run locally (unit tests only, no Docker):

```
mvn -B -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
open target/pit-reports/index.html
```

Baseline (first measurement, JDK 27 locally): 195 of 263 mutants killed (74%), line coverage of mutated
classes 90%, test strength 81%. The build fails below `pit.mutation.threshold` in `pom.xml`, set to 70 (the
baseline minus a margin for JDK differences). Raise it as surviving mutants are killed; never lower it to make
a run pass. The weakest area is `Hl7V2Parser` (survivors in field and component index handling).
