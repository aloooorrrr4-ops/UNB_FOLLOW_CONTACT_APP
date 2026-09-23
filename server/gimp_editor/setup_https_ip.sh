#!/usr/bin/env bash
set -euo pipefail

PUBLIC_IP="${PUBLIC_IP:-91.98.126.167}"
WEBROOT="${WEBROOT:-/var/www/letsencrypt}"
CERTBOT_VENV="${CERTBOT_VENV:-/opt/unb-certbot}"
SITE="/etc/nginx/sites-available/unb-pro-editor"
CERT_DIR="/etc/letsencrypt/live/$PUBLIC_IP"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo bash setup_https_ip.sh" >&2
  exit 1
fi

echo "[1/6] Installing Nginx and Certbot runtime..."
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y nginx python3 python3-venv curl

if [[ ! -x "$CERTBOT_VENV/bin/certbot" ]]; then
  python3 -m venv "$CERTBOT_VENV"
fi
"$CERTBOT_VENV/bin/pip" install --upgrade pip wheel
"$CERTBOT_VENV/bin/pip" install --upgrade "certbot>=5.4"

echo "[2/6] Preparing ACME HTTP challenge..."
mkdir -p "$WEBROOT/.well-known/acme-challenge"
cat > "$SITE" <<NGINX_HTTP
server {
    listen 80;
    listen [::]:80;
    server_name $PUBLIC_IP;

    location ^~ /.well-known/acme-challenge/ {
        root $WEBROOT;
        default_type text/plain;
    }

    location / {
        return 308 https://\$host\$request_uri;
    }
}
NGINX_HTTP

rm -f /etc/nginx/sites-enabled/default
ln -sfn "$SITE" /etc/nginx/sites-enabled/unb-pro-editor
nginx -t
systemctl enable --now nginx
systemctl reload nginx

echo "[3/6] Requesting a short-lived public IP certificate..."
"$CERTBOT_VENV/bin/certbot" certonly   --non-interactive   --agree-tos   --register-unsafely-without-email   --preferred-profile shortlived   --webroot   --webroot-path "$WEBROOT"   --ip-address "$PUBLIC_IP"

test -s "$CERT_DIR/fullchain.pem"
test -s "$CERT_DIR/privkey.pem"

echo "[4/6] Enabling HTTPS reverse proxy..."
cat > "$SITE" <<NGINX_TLS
server {
    listen 80;
    listen [::]:80;
    server_name $PUBLIC_IP;

    location ^~ /.well-known/acme-challenge/ {
        root $WEBROOT;
        default_type text/plain;
    }

    location / {
        return 308 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name $PUBLIC_IP;

    ssl_certificate $CERT_DIR/fullchain.pem;
    ssl_certificate_key $CERT_DIR/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    client_max_body_size 82m;
    proxy_read_timeout 130s;
    proxy_send_timeout 130s;

    location / {
        proxy_pass http://127.0.0.1:18083;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
NGINX_TLS

nginx -t
systemctl reload nginx

echo "[5/6] Installing automatic short-lived certificate renewal..."
cat > /etc/systemd/system/unb-certbot-renew.service <<EOF
[Unit]
Description=Renew UNB Pro Editor IP TLS certificate
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=$CERTBOT_VENV/bin/certbot renew --quiet
ExecStartPost=/bin/systemctl reload nginx
EOF

cat > /etc/systemd/system/unb-certbot-renew.timer <<'EOF'
[Unit]
Description=Renew UNB Pro Editor TLS certificate regularly

[Timer]
OnCalendar=*-*-* 00,06,12,18:17:00
Persistent=true
RandomizedDelaySec=900

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now unb-certbot-renew.timer

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
  ufw allow 80/tcp
  ufw allow 443/tcp
fi

echo "[6/6] Verifying HTTPS..."
curl --fail --show-error --silent "https://$PUBLIC_IP/health"
echo
STATUS="$(curl --silent --output /tmp/unb-caps.json --write-out '%{http_code}'   "https://$PUBLIC_IP/api/editor/capabilities")"
cat /tmp/unb-caps.json
echo
if [[ "$STATUS" != "401" ]]; then
  echo "Expected authenticated API boundary (401), got $STATUS" >&2
  exit 3
fi

echo "HTTPS ready at https://$PUBLIC_IP"
