#!/usr/bin/env python3
"""Generate protocol test vectors from the real yalexs-ble implementation.

Runs the unmodified yalexs-ble ``Lock.connect`` / ``lock_status`` /
``force_unlock`` / ``disconnect`` code against a simulated lock and records
every GATT event, so the Kotlin port can be replayed against it byte for byte.

Only a fixed DUMMY key is used. Never point this at a real credential.

Usage (bleak is stubbed, only ``cryptography`` is needed):

    git clone https://github.com/Yale-Libs/yalexs-ble && git -C yalexs-ble checkout v3.3.1
    python3 -I tools/gen_vectors.py yalexs-ble/src > wear/src/test/resources/yalexs_ble_3_3_1_transcript.json
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import sys
import types

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

DUMMY_KEY = bytes.fromhex("00112233445566778899aabbccddeeff")
DUMMY_SLOT = 3
MOBILE_RANDOM = bytes(range(0xA0, 0xB0))
LOCK_RANDOM = bytes(range(0x10, 0x18))

WRITE = "bd4ac611-0b45-11e3-8ffd-0800200c9a66"
READ = "bd4ac612-0b45-11e3-8ffd-0800200c9a66"
SECURE_WRITE = "bd4ac613-0b45-11e3-8ffd-0800200c9a66"
SECURE_READ = "bd4ac614-0b45-11e3-8ffd-0800200c9a66"
CHANNEL = {WRITE: "SESSION", READ: "SESSION", SECURE_WRITE: "SECURE", SECURE_READ: "SECURE"}


def install_stubs() -> None:
    """Stand-ins for bleak & co. so the library imports without a radio."""

    class BleakError(Exception):
        pass

    bleak = types.ModuleType("bleak")
    bleak.BleakError = BleakError
    bleak.BleakClient = object
    backends = types.ModuleType("bleak.backends")
    device = types.ModuleType("bleak.backends.device")
    device.BLEDevice = object
    scanner = types.ModuleType("bleak.backends.scanner")
    scanner.AdvertisementData = object
    brc = types.ModuleType("bleak_retry_connector")
    brc.BleakError = BleakError
    brc.MAX_CONNECT_ATTEMPTS = 1
    brc.BleakClientWithServiceCache = object
    brc.BLEDevice = object

    async def establish_connection(*_args, **_kwargs):
        return FAKE_CLIENT

    brc.establish_connection = establish_connection
    interrupt_mod = types.ModuleType("async_interrupt")

    @contextlib.asynccontextmanager
    async def interrupt(*_args, **_kwargs):
        yield

    interrupt_mod.interrupt = interrupt
    for name, mod in {
        "bleak": bleak,
        "bleak.backends": backends,
        "bleak.backends.device": device,
        "bleak.backends.scanner": scanner,
        "bleak_retry_connector": brc,
        "async_interrupt": interrupt_mod,
    }.items():
        sys.modules[name] = mod


def simple_checksum(buf: bytes) -> int:
    return (-sum(buf[:0x12])) & 0xFF


def security_checksum(buf: bytes) -> int:
    v = [int.from_bytes(buf[a:b], "little") for a, b in ((0, 4), (4, 8), (8, 0x12))]
    return (-(v[0] + v[1] + v[2])) & 0xFFFFFFFF


class SimulatedLock:
    """The lock side, written independently from the library's mobile side."""

    def __init__(self) -> None:
        self.secure_key = DUMMY_KEY
        self.cbc_rx = None
        self.cbc_tx = None
        self.status = 0x05  # LOCKED

    def _ecb(self, key: bytes, data: bytes, decrypt: bool) -> bytes:
        c = Cipher(algorithms.AES(key), modes.ECB())
        ctx = c.decryptor() if decrypt else c.encryptor()
        return ctx.update(data)

    def _secure_reply(self, first: int, payload: bytes = b"") -> bytearray:
        resp = bytearray(0x12)
        resp[0] = first
        resp[4 : 4 + len(payload)] = payload
        resp[0x10] = 0x0F
        resp[0x11] = DUMMY_SLOT
        resp[0x0C:0x10] = security_checksum(resp).to_bytes(4, "little")
        return resp

    def _session_reply(self, flag: int, op: int, kind: int = 0, value: int = 0) -> bytes:
        resp = bytearray(0x12)
        resp[0], resp[1], resp[4], resp[8] = flag, op, kind, value
        resp[3] = simple_checksum(resp)
        assert simple_checksum(resp) == 0
        resp[0:16] = self.cbc_tx.update(bytes(resp[0:16]))
        return bytes(resp)

    def on_write(self, char: str, data: bytes) -> list[tuple[str, bytes]]:
        if char == SECURE_WRITE:
            plain = self._ecb(self.secure_key, data[:16], True) + data[16:]
            assert int.from_bytes(plain[0x0C:0x10], "little") == security_checksum(plain)
            op = plain[0]
            if op == 0x01:
                assert plain[0x11] == DUMMY_SLOT
                resp = self._secure_reply(0x02, LOCK_RANDOM)
                out = self._ecb(self.secure_key, bytes(resp[:16]), False) + bytes(resp[16:])
                self.secure_key = plain[4:12] + LOCK_RANDOM
                key = self.secure_key
                self.cbc_rx = Cipher(algorithms.AES(key), modes.CBC(bytes(16))).decryptor()
                self.cbc_tx = Cipher(algorithms.AES(key), modes.CBC(bytes(16))).encryptor()
                return [(SECURE_READ, out)]
            reply = {0x03: 0x04, 0x05: 0x8B}[op]
            resp = self._secure_reply(reply)
            return [(SECURE_READ, self._ecb(self.secure_key, bytes(resp[:16]), False) + bytes(resp[16:]))]

        plain = self.cbc_rx.update(data[:16]) + data[16:]
        assert simple_checksum(plain) == 0 and plain[0] == 0xEE
        op = plain[1]
        if op == 0x02:
            # Reply, then an unsolicited status push the client must still decrypt.
            return [
                (READ, self._session_reply(0xBB, 0x02, 0x02, self.status)),
                (READ, self._session_reply(0xBB, 0x02, 0x2E, 0x01)),
            ]
        if op == 0x0A:
            self.status = 0x03
            return [(READ, self._session_reply(0xAA, 0x0A))]
        raise AssertionError(f"unexpected session opcode {op:#x}")


class FakeServices:
    def get_characteristic(self, uuid: str) -> str:
        return uuid


class FakeClient:
    def __init__(self) -> None:
        self.services = FakeServices()
        self.is_connected = True
        self.callbacks = {}
        self.events: list[dict] = []
        self.lock = SimulatedLock()

    async def start_notify(self, char, callback) -> None:
        self.events.append({"type": "enable", "channel": CHANNEL[char]})
        self.callbacks[char] = callback

    async def stop_notify(self, char) -> None:
        self.events.append({"type": "disable", "channel": CHANNEL[char]})

    async def write_gatt_char(self, char, data, response) -> None:
        assert response is True
        self.events.append({"type": "write", "channel": CHANNEL[char], "data": bytes(data).hex()})
        loop = asyncio.get_running_loop()
        for notify_char, payload in self.lock.on_write(char, bytes(data)):
            self.events.append({"type": "notify", "channel": CHANNEL[notify_char], "data": payload.hex()})
            loop.call_soon(self.callbacks[notify_char], notify_char, bytearray(payload))

    async def disconnect(self) -> None:
        self.is_connected = False


FAKE_CLIENT: FakeClient


async def run(src: str) -> dict:
    global FAKE_CLIENT
    install_stubs()
    pkg = types.ModuleType("yalexs_ble")
    pkg.__path__ = [f"{src}/yalexs_ble"]
    sys.modules["yalexs_ble"] = pkg
    import yalexs_ble.lock as lock_mod  # noqa: PLC0415

    lock_mod.os = types.SimpleNamespace(urandom=lambda n: MOBILE_RANDOM[:n])
    FAKE_CLIENT = FakeClient()
    states = []
    lock = lock_mod.Lock(lambda: None, DUMMY_KEY.hex(), DUMMY_SLOT, "vectors", states.append)
    await lock.connect()
    status = await lock.lock_status()
    await asyncio.sleep(0.05)  # let the unsolicited push land before the next command
    await lock.force_unlock()
    await lock.disconnect()
    return {
        "source": "yalexs-ble 3.3.1, unmodified, against tools/gen_vectors.py SimulatedLock",
        "dummy_key": DUMMY_KEY.hex(),
        "slot": DUMMY_SLOT,
        "mobile_random": MOBILE_RANDOM.hex(),
        "status_before_unlock": status.name,
        "events": FAKE_CLIENT.events,
    }


if __name__ == "__main__":
    print(json.dumps(asyncio.run(run(sys.argv[1])), indent=2))
