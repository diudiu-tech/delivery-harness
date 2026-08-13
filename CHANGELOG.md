# Changelog

All notable changes to this project will be documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to use [Semantic Versioning](https://semver.org/) after its first release.

## [Unreleased]

### Added

- English and Simplified Chinese documentation.
- MIT license and GitHub community health files.
- Maven Wrapper, CI, CodeQL, and Dependabot configuration.
- Unit and application smoke tests.

### Changed

- Upgraded the build baseline to Java 17 and Spring Boot 3.5.
- Reduced dependencies to components used by the in-memory MVP.
- Pinned the optional Ollama container and bound it to localhost.
- Bound the API to localhost by default and aligned the pre-release version at `0.1.0-SNAPSHOT`.

### Fixed

- Rejected invalid text-chunk overlap values that could prevent loop progress.
- Reused request trace IDs throughout workflow execution.
- Removed internal exception details from generic API errors.
- Reported advisory guardrail failures accurately in workflow steps.
- Validated evaluation cases, collection elements, and cross-field chunk settings.
- Returned consistent HTTP 404/502 statuses for missing resources and dependency failures.
- Bounded model-provider response bodies and hardened compensation amount parsing.
- Removed Mockito from the test runtime to avoid JDK agent-attachment failures.
