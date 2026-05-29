# Security Policy

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions, or pull requests.**

Instead, use GitHub's **private vulnerability reporting**:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability** (under "Private vulnerability reporting").
3. Provide a description, reproduction steps, affected versions, and the impact.

This opens a private channel visible only to the maintainers. We aim to
acknowledge reports within a few business days and will coordinate a fix and
disclosure timeline with you.

## Scope

This project is designed for deployment inside security-sensitive environments
(e.g. financial institutions). Reports that are especially valuable include:

- Authentication/authorization bypass on the MCP, webhook, admin, or SCIP-upload endpoints
- Webhook signature (HMAC) verification flaws or fail-open behavior
- Secret/credential exposure (config handling, logs, error messages)
- SQL injection or other injection in the query layer
- SSRF, path traversal, or arbitrary file read via repo/clone handling

## Supported versions

This project is pre-1.0 and under active development; security fixes are applied
to `main`. Please test against the latest `main` before reporting.
