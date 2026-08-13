# Security Policy

## Supported versions

This repository is a pre-1.0 reference implementation. Security fixes are applied only to the latest commit on `main`; no released version is currently supported for production use.

## Reporting a vulnerability

Please do not disclose vulnerabilities in public issues, pull requests, discussions, or logs. Use GitHub's **Report a vulnerability** / private vulnerability reporting feature after the repository is published. If that feature is unavailable, contact the repository owner privately.

Include the affected component, impact, reproduction steps or proof of concept, and any suggested mitigation. Do not include real personal data, production credentials, or data obtained without authorization. You can expect an acknowledgement when a maintainer receives the report; remediation timelines depend on severity and maintainer availability.

## Deployment warning

The current code has no authentication, authorization, tenant isolation, or rate limiting. It uses unbounded in-memory demonstration stores and sends assembled workflow context to the configured model endpoint. It must not be exposed to untrusted networks or used with real order/customer data without a separate security review and additional controls.

At minimum, a production derivative should add strong identity and authorization, request/body quotas, secrets management, network isolation, data minimization and redaction, prompt-injection controls, model-provider governance, durable audit logs, retention policies, dependency/container scanning, and human approval for consequential actions.
