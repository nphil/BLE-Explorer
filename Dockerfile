FROM nginxinc/nginx-unprivileged:stable-alpine
LABEL org.opencontainers.image.title="BlueShark"
LABEL org.opencontainers.image.description="Local-first BLE capture analysis; Home Assistant controls devices through its shared Bluetooth stack."
LABEL org.opencontainers.image.source="https://github.com/nphil/BlueShark"

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/

EXPOSE 8080
