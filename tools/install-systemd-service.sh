#!/usr/bin/env bash
# TurboMesh Desktop - Systemd Daemon Installer

set -e

SERVICE_DIR="$HOME/.config/systemd/user"
SERVICE_FILE="$SERVICE_DIR/turbomesh.service"

# Ensure the executable exists
if [ ! -f "build/compose/binaries/main/app/turbomesh/bin/turbomesh" ]; then
    echo "TurboMesh binary not found. Please run './gradlew packageDistributionForCurrentOS' first."
    exit 1
fi

APP_PATH="$(pwd)/build/compose/binaries/main/app/turbomesh/bin/turbomesh"

echo "Creating systemd user service for TurboMesh at $SERVICE_FILE..."
mkdir -p "$SERVICE_DIR"

cat > "$SERVICE_FILE" << EOF
[Unit]
Description=TurboMesh Background Daemon
Documentation=https://github.com/gdev6145/turbomesh-desktop
After=network.target

[Service]
Type=simple
ExecStart=$APP_PATH --headless
Restart=on-failure
RestartSec=5
# Optional: isolate memory/cpu for background tasks
MemoryAccounting=true
MemoryHigh=250M
MemoryMax=500M

[Install]
WantedBy=default.target
EOF

echo "Reloading systemd daemon..."
systemctl --user daemon-reload

echo "Service created successfully!"
echo "To start the daemon:    systemctl --user start turbomesh"
echo "To enable on boot:      systemctl --user enable turbomesh"
echo "To check status:        systemctl --user status turbomesh"
