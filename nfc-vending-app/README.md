# NFC Vending App (prototype)

This project is a fresh Android app based on protocol analysis from decompiled Weimi app components.

Implemented:
- Card number display from:
  - Android NFC (`NfcAdapter`), and
  - serial input (`/dev/ttyS4`) for external reader responses.
- Numeric keypad + Enter flow.
- Serial command sending over RS485 (`/dev/ttyS4`).
- Debug write mirror to `/dev/ttyS3`.

Hardware/protocol basis extracted from decompiled app:
- `SzzkSerialPort` native serial open pattern.
- Motor commands:
  - `cmd=40` (`setShipments`) for spiral vend.
  - `cmd=86` (`unlockRFIDDoor`) for locker/door.
- Frame skeleton: `0xEE`, version `0x01`, address, cmd, length, payload, CRC16.

## Code format (numeric)
- `1BBLLD` => open locker
  - `BB` board address
  - `LL` lock number
  - `D` door number
- `2BBCCX` => vend spiral product
  - `BB` board address
  - `CC` channel number
  - `X` reserved

## Real mapping (recommended)
App first checks `app/src/main/assets/code_map.json`.  
If entered code exists there, it sends mapped action directly:
- `type=locker` => open locker command (`cmd=86`)
- `type=spiral` => vend command (`cmd=40`)

Only if code is not in map, app falls back to 6-digit format above.

## Important note
CRC and final command semantics can vary by board firmware. If the board does not respond, verify:
1. CRC byte order
2. board address mapping
3. channel/lock indexing
4. expected baud/parity/stop bits for your control board.
