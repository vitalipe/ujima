# UjimaOS Overview

UjimaOS is a lightweight operating system and management platform designed for shared public computers in remote or underdeveloped areas.

The project originated from real classroom deployments in northern Tanzania near Mount Meru, including deployments at Nkoarisambu Secondary School and Peace Matunda Primary School.

The original deployments used low-power x86 systems (Intel N100 NUCs) and later Raspberry Pi 5 based systems running customized Ubuntu installations with offline educational content, content filtering, caching, and lightweight desktop customization.

The project goals from the beginning were practical and operational:
- provide affordable public computer access
- use low-power and easy-to-ship hardware
- support offline educational content
- minimize infrastructure requirements
- build systems that could realistically operate in remote areas

Over several years of operating these classrooms, we collected practical feedback about what worked well and what created operational friction.

Modern low-power hardware proved reliable, inexpensive, power efficient, and operationally viable. The main challenges were software deployment, maintenance, classroom management, desktop lockdown, upgrades, and long-term operational simplicity.

We were specifically interested in systems designed for:
- public/shared computer usage
- offline-first operation, where internet access is optional rather than the core experience
- cheap lightweight hardware
- simple local administration and management
- robust local operation with easy recovery and no single point of failure
- minimal infrastructure environments

While many existing tools solve parts of these problems, we did not find a system focused specifically on lightweight, offline-first, peer-managed public desktop computing.

Typical classroom deployments often require:

- network infrastructure and configuration
- centralized management server (cloud or local)
- installation and provisioning workflows
- desktop lockdown configuration
- content distribution system
- ongoing technical maintenance

In practice, these requirements make small computer labs difficult to sustain in remote low-resource environments.

UjimaOS attempts to reduce this operational burden by providing a local-first, serverless, peer-managed system focused on reliability, simplicity, and recoverability.

The system is designed around several assumptions:

- internet connectivity may be unreliable or expensive
- power outages are common
- technical support may be minimal or unavailable
- deployments should be inexpensive to expand
- deployments can often begin with only 3-4 computers and expand later
- site operators are often non-technical users

Rather than treating classroom computers as centrally managed thin clients, UjimaOS treats each machine as a self-contained peer capable of participating in a shared local environment called a circle.