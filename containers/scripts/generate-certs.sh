#!/usr/bin/env bash
# Generate a local development certificate authority and the server
# certificates the Hive stack needs.
#
#   cd containers && ./scripts/generate-certs.sh
#
# Produces, in containers/certs/ (gitignored -- these are secrets):
#   hive-ca.crt / hive-ca.key         the local CA
#   hive-frontend.crt / .key          nginx
#   hive-idp.crt / .key               Keycloak
#   hive-backend.crt / .key           informational
#   hive-backend.p12                  what Spring Boot actually loads
#
# These certificates are for local development only. They are signed by a CA
# that exists on one machine and are not fit for any deployed environment.

set -euo pipefail

# Git Bash / MSYS on Windows rewrites any argument that looks like a POSIX path
# into a Windows path, which turns openssl's "/CN=..." subject into
# "C:/Program Files/Git/CN=..." and fails. Disabling that conversion is a no-op
# on Linux and macOS.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")/.."
CERT_DIR="certs"
DAYS=825   # the maximum modern browsers accept for a leaf certificate

if [[ -f .env ]]; then
    # shellcheck disable=SC1091
    set -a; source .env; set +a
fi
CERT_PASSWORD="${CERT_PASSWORD:-changeit}"

if ! command -v openssl > /dev/null 2>&1; then
    echo "openssl is required but was not found on PATH." >&2
    exit 1
fi

mkdir -p "$CERT_DIR"

# --- The CA -----------------------------------------------------------------
if [[ ! -f "$CERT_DIR/hive-ca.crt" ]]; then
    echo "==> Creating local development CA"
    openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
        -keyout "$CERT_DIR/hive-ca.key" \
        -out    "$CERT_DIR/hive-ca.crt" \
        -subj   "/CN=Hive Local Development CA/O=Hive/C=US" \
        -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
        -addext "keyUsage=critical,keyCertSign,cRLSign"
else
    echo "==> Reusing existing CA at $CERT_DIR/hive-ca.crt"
fi

# --- Leaf certificates ------------------------------------------------------
# Each service is reachable under two names: the compose service name (how
# containers reach each other) and localhost (how the developer's browser
# reaches it). Both must be in the SAN or one of the two paths fails.
issue_cert() {
    local name="$1" cn="$2" sans="$3"

    echo "==> Issuing certificate for $name"
    openssl req -newkey rsa:2048 -nodes \
        -keyout "$CERT_DIR/$name.key" \
        -out    "$CERT_DIR/$name.csr" \
        -subj   "/CN=$cn/O=Hive/C=US"

    # A real temp file rather than process substitution: a native Windows
    # openssl cannot open the /dev/fd/NN path that <(...) hands it.
    local ext="$CERT_DIR/$name.ext"
    {
        echo "subjectAltName=$sans"
        echo "basicConstraints=CA:FALSE"
        echo "keyUsage=critical,digitalSignature,keyEncipherment"
        echo "extendedKeyUsage=serverAuth"
    } > "$ext"

    openssl x509 -req -in "$CERT_DIR/$name.csr" \
        -CA "$CERT_DIR/hive-ca.crt" -CAkey "$CERT_DIR/hive-ca.key" -CAcreateserial \
        -out "$CERT_DIR/$name.crt" -days "$DAYS" -sha256 \
        -extfile "$ext"

    rm -f "$CERT_DIR/$name.csr" "$ext"
}

issue_cert hive-frontend localhost   "DNS:localhost,DNS:frontend,DNS:hive-frontend,IP:127.0.0.1"
issue_cert hive-backend  hive-backend "DNS:localhost,DNS:backend,DNS:hive-backend,IP:127.0.0.1"
issue_cert hive-idp      idp          "DNS:localhost,DNS:idp,DNS:hive-idp,IP:127.0.0.1"

# --- Spring Boot keystore ---------------------------------------------------
# Spring Boot's embedded server loads a PKCS#12 keystore, not a PEM pair.
echo "==> Packaging the backend certificate as PKCS#12"
openssl pkcs12 -export \
    -in  "$CERT_DIR/hive-backend.crt" \
    -inkey "$CERT_DIR/hive-backend.key" \
    -certfile "$CERT_DIR/hive-ca.crt" \
    -name hive-backend \
    -out "$CERT_DIR/hive-backend.p12" \
    -passout "pass:$CERT_PASSWORD"

chmod 600 "$CERT_DIR"/*.key "$CERT_DIR"/*.p12 2>/dev/null || true

cat <<EOF

Done. Certificates are in $(pwd)/$CERT_DIR

Trust the CA so the browser accepts the stack without warnings. This matters
more than usual here: nginx sends HSTS with a two-year max-age, and once a
browser has seen that header for localhost it will refuse to fall back to
plaintext -- a click-through exception is not enough.

  Windows  certutil -addstore -user Root certs\\hive-ca.crt
  macOS    sudo security add-trusted-cert -d -r trustRoot \\
             -k /Library/Keychains/System.keychain $CERT_DIR/hive-ca.crt
  Linux    sudo cp $CERT_DIR/hive-ca.crt /usr/local/share/ca-certificates/ \\
             && sudo update-ca-certificates

To remove it later on Windows: certutil -delstore -user Root "Hive Local Development CA"
EOF
