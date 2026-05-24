# Design Goals

UjimaOS prioritizes operational simplicity over flexibility.

The primary goal is not to create a general-purpose Linux distribution, but a robust shared-computing appliance suitable for daily use in classrooms, libraries, community technology centers and  other public-access environments.

Core goals include:

- offline-first operation
- serverless deployment
- low deployment cost
- low maintenance overhead
- rollback-safe upgrades and simple recovery workflows
- minimal infrastructure requirements
- support for unreliable power and networking conditions
- no single point of failure (peer-managed administration)
- lightweight hardware requirements

The system is intentionally opinionated.

Administrators are expected to be teachers, volunteers, librarians, or other non-technical users rather than Linux system administrators. UjimaOS therefore favors constrained, predictable management workflows over unrestricted system access.

A concise summary of the design philosophy is:

> Linux underneath, appliance operational model above.
