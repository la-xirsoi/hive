#!/bin/sh
# Backend container entrypoint.
#
# Its only job beyond exec'ing the jar is the truststore. The stack's internal
# TLS is signed by the development CA that generate-certs.sh produced, and the
# backend makes one outbound HTTPS call -- fetching the identity provider's
# JWKS -- that fails with a PKIX path error unless the JVM trusts that CA.
# The CA is mounted at run time, so this cannot be baked into the image.
set -eu

CA_CERT="${HIVE_CA_CERT:-/app/certs/hive-ca.crt}"
TRUST_STORE="/tmp/hive-truststore.p12"
TRUST_STORE_PASSWORD="changeit"

if [ -r "$CA_CERT" ]; then
    # Seed from the JDK's own cacerts rather than starting empty, so trusting
    # the local CA does not quietly untrust every public one.
    cp "${JAVA_HOME}/lib/security/cacerts" "$TRUST_STORE"
    keytool -importcert -noprompt         -alias hive-ca         -file "$CA_CERT"         -keystore "$TRUST_STORE"         -storepass "$TRUST_STORE_PASSWORD" > /dev/null
    JAVA_OPTS="${JAVA_OPTS:-} -Djavax.net.ssl.trustStore=${TRUST_STORE} -Djavax.net.ssl.trustStorePassword=${TRUST_STORE_PASSWORD}"
    echo "entrypoint: trusting $CA_CERT"
else
    echo "entrypoint: no CA at $CA_CERT; using the JVM default truststore" >&2
fi

# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/hive.jar
