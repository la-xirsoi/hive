#!/bin/sh
# Teach nginx the container runtime's DNS server.
#
# nginx resolves a literal upstream name once, at configuration load, and then
# caches that address for the life of the process. Restart the identity provider
# and it comes back on a new address while nginx keeps dialling the old one --
# every proxied request 502s until nginx itself is restarted, which is an
# unpleasant thing to have to know. Re-resolution requires a `resolver`
# directive, which cannot be inherited from /etc/resolv.conf, and the address to
# put in it is assigned by the runtime and differs per network. So it is read
# from resolv.conf here, at startup, and written where nginx will include it.
#
# nginx:alpine runs every executable in /docker-entrypoint.d before starting.
set -eu

CONF="/etc/nginx/conf.d/00-resolver.conf"

# ipv6=off: the compose network is v4-only, and without it every lookup pays for
# a AAAA query that can only fail.
NAMESERVERS=$(awk '/^nameserver/ { printf "%s ", $2 }' /etc/resolv.conf)

if [ -n "$NAMESERVERS" ]; then
    echo "resolver ${NAMESERVERS}valid=10s ipv6=off;" > "$CONF"
    echo "resolver_timeout 5s;" >> "$CONF"
    echo "hive: nginx resolver set to ${NAMESERVERS}"
else
    # No resolver: the upstream variables below will not resolve, so say so
    # loudly rather than letting it look like an upstream outage.
    echo "hive: no nameserver in /etc/resolv.conf; proxied upstreams will fail" >&2
    : > "$CONF"
fi
