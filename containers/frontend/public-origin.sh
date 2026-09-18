#!/bin/sh
# Tell nginx the origin the browser actually uses.
#
# The plaintext listener's job is to redirect to HTTPS, and it cannot work that
# out for itself: $host is the Host header with the port stripped, so
# `https://$host` sends the browser to port 443, where nothing listens. The
# published port is a fact of the compose file, not of the container, so it has
# to be passed in.
#
# nginx:alpine runs every executable in /docker-entrypoint.d before starting.
set -eu

CONF="/etc/nginx/hive-public-origin.conf"

if [ -n "${HIVE_PUBLIC_ORIGIN:-}" ]; then
    printf 'set $hive_public_origin "%s";\n' "$HIVE_PUBLIC_ORIGIN" > "$CONF"
    echo "hive: plaintext redirects to $HIVE_PUBLIC_ORIGIN"
else
    # Better than nothing and still correct when the stack is published on 443.
    printf 'set $hive_public_origin "https://$host";\n' > "$CONF"
    echo "hive: HIVE_PUBLIC_ORIGIN unset; redirecting to https://\$host (port 443)" >&2
fi
