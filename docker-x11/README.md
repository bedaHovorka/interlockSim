# Docker X11 SELinux Policy Modules

This directory contains pre-generated SELinux policy modules for enabling Docker containers to access the X11 display server on Fedora Linux with SELinux enforcing mode.

## Files

- **docker-x11-complete.{pp,te}** (recommended) - Comprehensive policy allowing all required X11 operations
- **docker-x11-final.{pp,te}** - Alternative comprehensive policy (latest iteration)
- **docker-x11-java.{pp,te}** - Initial Java-specific policy (may be incomplete)

Each policy has two files:
- `.te` - SELinux policy source (Type Enforcement)
- `.pp` - Compiled policy module (Policy Package)

## Installation

Install the recommended policy module:

```bash
sudo semodule -i docker-x11/docker-x11-complete.pp
```

## Verification

Check that the module is loaded:

```bash
sudo semodule -l | grep docker-x11
```

## What This Fixes

These policies allow Docker containers to:
- Connect to X11 unix stream socket (`connectto` permission)
- Read X11 authentication files (`read` permission for xauthority)

This is required for GUI applications running in Docker containers on Fedora.

## Documentation

For complete setup instructions, see:
- [docs/FEDORA_DOCKER_X11_SETUP.md](../docs/FEDORA_DOCKER_X11_SETUP.md)

## Regenerating Policies

If you need to regenerate these policies:

```bash
# Generate policy from audit log
sudo ausearch -c 'java' --raw | sudo audit2allow -M docker-x11-custom

# Install the policy
sudo semodule -i docker-x11-custom.pp
```

## System Requirements

- Fedora Linux (tested on Kernel 6.18.5-200.fc43.x86_64)
- SELinux in enforcing mode
- Docker or Docker Compose

## Security Considerations

These policies grant specific X11 access permissions to Docker containers. They are safe for local development but should be reviewed before use in production or multi-user environments.
