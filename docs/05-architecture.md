# System Architecture

UjimaOS currently consists of four primary layers.

## 1. Target

A **target** is the platform-specific implementation layer.

Targets abstract:

- CPU architecture
- boot process
- partition layout
- desktop environment
- platform configuration
- deployment logic
- runtime system operations

Targets implement both runtime behavior and deployment behavior.

Examples include:

- Raspberry Pi based targets
- mock/testing targets

The desktop environment is considered part of the target abstraction rather than a separate subsystem.

Persistent and temporary desktop configuration changes are handled through the target itself.

Examples include:

- wallpaper configuration
- keyboard layouts
- screen resolution
- desktop restrictions
- launcher behavior

## 2. Agent

Each machine runs a local Ujima agent.

The agent is responsible for:

- peer discovery
- event monitoring
- USB token detection
- persistent settings reconciliation
- system coordination

The agent enforces persistent configuration during boot while allowing temporary runtime operations during active sessions.

## 3. HTTP and CLI Interfaces

The system exposes HTTP and CLI interfaces over the agent and runtime functionality.

These interfaces are used for:

- local administration
- peer administration
- diagnostics
- automation
- deployment operations

The HTTP interface is intentionally used as the primary management abstraction.

## 4. Web-Based Administration UI

Administration is performed through web-based interfaces layered on top of the HTTP APIs.

The web-based approach was chosen partly to allow future portability across different operating system bases and appliance environments.

The UI allows administrators to:

- discover nearby peers
- manage sessions
- configure systems
- install applications
- launch content
- reboot or shut down systems
- perform upgrades and rollback operations
