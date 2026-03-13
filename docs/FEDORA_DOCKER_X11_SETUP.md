# Fedora Docker X11 GUI Setup

## Problem
When running Docker containers with GUI applications on Fedora (with SELinux enabled), you may encounter AVC denial errors preventing X11 display forwarding.

## Available SELinux Policies

This project includes three pre-generated SELinux policy modules in the `desktop-ui/docker-x11/` directory:

- **docker-x11-complete** (recommended) - Comprehensive policy allowing all required X11 operations
- **docker-x11-final** - Alternative comprehensive policy (latest iteration)
- **docker-x11-java** - Initial Java-specific policy (may be incomplete)

**Recommendation:** Use `docker-x11-complete.pp` unless you encounter specific issues.

## Solution

### 1. Allow Docker to Access X11 (xhost)

Run this command to allow local Docker containers to connect to your X11 server:

```bash
xhost +local:docker
```

This command allows any process running as root in Docker containers to connect to your X11 server.

**Note:** This needs to be run after each reboot or login session.

### 2. SELinux Policy Configuration

Generate and install SELinux policies to permanently allow Docker containers to access X11:

```bash
# Generate policy from audit log
sudo ausearch -c 'java' --raw | sudo audit2allow -M docker-x11-complete

# Install the policy
sudo semodule -i docker-x11-complete.pp
```

**Note:** Pre-generated policy files are available in the `desktop-ui/docker-x11/` directory of this project. You can install them directly:

```bash
# Install pre-generated policy
sudo semodule -i desktop-ui/docker-x11/docker-x11-complete.pp
```

### 3. Verify Configuration

Check that SELinux modules are loaded:

```bash
sudo semodule -l | grep docker-x11
```

Expected output (you may have one or more of these):
```
docker-x11-complete
docker-x11-final
docker-x11-java
```

**Note:** You may need to generate and install multiple policies as new denials are discovered. Simply repeat steps 2-3 whenever you encounter new AVC denials.

### 4. Run GUI Application

```bash
docker compose up app
```

## What This Fixes

The SELinux policies allow:
- **connectto** - Docker containers (Java processes) to connect to X11 unix stream socket
- **read** - Docker containers to read X11 authentication files (xauthority)

## Making xhost Persistent

To avoid running `xhost +local:docker` after each login, add it to your shell startup:

**For bash (~/.bashrc):**
```bash
# Allow Docker X11 access
if [ -n "$DISPLAY" ]; then
    xhost +local:docker >/dev/null 2>&1
fi
```

**For zsh (~/.zshrc):**
```zsh
# Allow Docker X11 access
if [[ -n "$DISPLAY" ]]; then
    xhost +local:docker >/dev/null 2>&1
fi
```

## Security Considerations

- `xhost +local:docker` allows any Docker container running as root to access your X11 server
- This is acceptable for local development but should not be used on shared/production systems
- For enhanced security, use X11 forwarding with proper authentication cookies instead

## Troubleshooting

### Check for new AVC denials:
```bash
sudo ausearch -m avc -ts recent
```

### View detailed SELinux messages:
```bash
sudo sealert -a /var/log/audit/audit.log
```

### Remove SELinux modules (if needed):
```bash
sudo semodule -r docker-x11-complete
sudo semodule -r docker-x11-java
```

## System Information

- **OS:** Fedora Linux (Kernel 6.18.5-200.fc43.x86_64)
- **Date Configured:** January 2026
- **SELinux Mode:** Enforcing
- **Docker Version:** Check with `docker --version`
- **Display Server:** X11 on :0

## Related Files

**Project Files:**
- `desktop-ui/docker-x11/docker-x11-complete.{pp,te}` - SELinux policy module (complete, recommended)
- `desktop-ui/docker-x11/docker-x11-final.{pp,te}` - SELinux policy module (final iteration)
- `desktop-ui/docker-x11/docker-x11-java.{pp,te}` - SELinux policy module (Java-specific, initial)
- `docker-compose.yml` - Docker Compose configuration with X11 forwarding

**System Files (after installation):**
- `/etc/selinux/targeted/modules/active/modules/docker-x11-complete.pp` - Installed SELinux policy module
- `/etc/selinux/targeted/modules/active/modules/docker-x11-java.pp` - Installed SELinux policy module

## References

- SELinux documentation: https://docs.fedoraproject.org/en-US/quick-docs/selinux-getting-started/
- audit2allow man page: `man audit2allow`
- xhost man page: `man xhost`
