FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# The Java image also inherits a Yarn repository, which this lab never uses.
# Remove it so a rotated Yarn signing key cannot block Debian package installs.
RUN rm -f /etc/apt/sources.list.d/yarn.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends maven python3 curl \
    && rm -rf /var/lib/apt/lists/*
