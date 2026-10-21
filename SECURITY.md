# Security Policy

## Supported versions

Only the **latest release** of VoidTube is supported with security fixes. Please update
before reporting an issue.

| Version | Supported |
|:--|:--|
| Latest release | ✅ |
| Older releases | ❌ |

## Reporting a vulnerability

Please **do not** open a public issue for a security problem. Instead:

1. Prefer GitHub's private reporting: go to the **Security** tab →
   **Report a vulnerability** (private advisory), or
2. Contact the maintainer on Telegram: **[t.me/VoidXDevs](https://t.me/VoidXDevs)**.

Please include:

- the VoidTube version (Settings › About) and your Android version,
- what the issue is and how to reproduce it,
- any proof-of-concept, logs, or screenshots.

You can expect an acknowledgement within a few days, and we will keep you updated on the
fix and disclosure timeline.

## Scope notes

VoidTube is a client app: it has **no backend of its own** and stores no account passwords
(sign-in uses the same device-code flow a TV uses). Reports about Google/YouTube services,
SmartTube, or NewTube themselves are out of scope — please report those upstream.
