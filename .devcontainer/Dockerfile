FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven python3 curl \
    && rm -rf /var/lib/apt/lists/*
