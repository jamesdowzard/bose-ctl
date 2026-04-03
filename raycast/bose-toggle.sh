#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bose Toggle
# @raycast.mode compact
# @raycast.icon 🎧
# @raycast.packageName Bose

BOSE_MAC="E4:58:BC:C0:2F:72"
BLUEUTIL="/opt/homebrew/bin/blueutil"
BOSE_CTL="$HOME/bin/bose-ctl"

connected=$($BLUEUTIL --is-connected "$BOSE_MAC")
if [ "$connected" = "1" ]; then
    $BLUEUTIL --disconnect "$BOSE_MAC"
    echo "Disconnected"
else
    $BLUEUTIL --connect "$BOSE_MAC"
    sleep 2
    battery=$($BOSE_CTL battery 2>/dev/null)
    echo "Connected ${battery}"
fi
