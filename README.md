# bose-ctl

Command-line tool to manage Bose QC Ultra headphone connections via reverse-engineered RFCOMM protocol.

## Build

```bash
swiftc -framework IOBluetooth -o bose-ctl BoseCtl.swift
```

## Usage

```
bose-ctl status              Show connection status
bose-ctl connect <device>    Connect a paired device
bose-ctl disconnect <device> Disconnect a device
bose-ctl swap <device>       Swap 2nd slot to this device
bose-ctl devices             List known device aliases
```

## Hammerspoon Integration

A Hammerspoon module is included for quick device switching via keyboard shortcut.

Add to your `~/.hammerspoon/init.lua`:

```lua
BoseCtl = dofile("/Users/jamesdowzard/code/personal/bose-ctl/hammerspoon/bose.lua")
BoseCtl.start()
```

Press `Ctrl+Alt+B` to open the device switcher popup.

## Protocol

The Bose Music app communicates with QC Ultra headphones over an RFCOMM channel (UUID `deca-fade`) using a binary protocol:

```
[block, function, operator, length, ...payload]
```

Operators: `0x01` GET, `0x03` RESP, `0x04` ERR, `0x05` START, `0x06` SET, `0x07` ACK

## Licence

MIT
