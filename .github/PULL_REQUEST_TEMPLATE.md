## Problem

<!-- What was wrong, or what was missing. Link the issue if there is one. -->

## Decision

<!-- What you did and why this approach. Mention alternatives you rejected and the reason. -->

## Verification

<!-- How you know it works. Name the tests. If it touches security, name the security test. -->

- [ ] `./gradlew test` is green
- [ ] `./gradlew test -PsecurityOnly` is green
- [ ] `./gradlew verifyPlugin` is green
- [ ] No secret, absolute machine path or user data in the diff
- [ ] Nothing is written inside a user's repository
- [ ] Documentation updated when the behaviour changed
