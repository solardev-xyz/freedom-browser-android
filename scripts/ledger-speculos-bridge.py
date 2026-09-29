#!/usr/bin/env python3
"""Stand-in for a Ledger's Bluetooth side, in front of Speculos (#142).

A debug build lists `files/ledger-dev-links` entries on its Connect Ledger
page (see app/src/debug/.../LedgerDevLinks.kt). Such a link speaks the
same frames a Bluetooth Ledger notifies — `0x05 | index(u16) | [len(u16)] |
data`, sized by the answer to the `0x08 0 0 0 0` MTU query — over TCP,
each frame with a two-byte length in front. This script unframes the
APDUs, hands them to Speculos's APDU port, and frames the answers back,
so everything the app does over Bluetooth except the GATT calls runs
against the real Ethereum app.

    speculos --model nanox --display headless --apdu-port 9999 app.elf &
    scripts/ledger-speculos-bridge.py --listen 8721 --speculos 127.0.0.1:9999 --mtu 23
    adb reverse tcp:8721 tcp:8721
    adb shell run-as baby.freedom.mobile sh -c \
        "echo 'Speculos Nano X=127.0.0.1:8721' > files/ledger-dev-links"

--mtu is the frame size the "device" announces: 23 (a small ATT MTU)
makes every APDU span several frames; 153 is what a Nano X uses.
"""

import argparse
import socket
import socketserver
import struct


def recv_exact(sock, n):
    out = b""
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise ConnectionError("closed")
        out += chunk
    return out


def frames(answer, mtu):
    out, index, offset = [], 0, 0
    while True:
        head = struct.pack(">BHH", 0x05, index, len(answer)) if index == 0 else struct.pack(">BH", 0x05, index)
        take = answer[offset:offset + mtu - len(head)]
        out.append(head + take)
        offset += len(take)
        index += 1
        if offset >= len(answer):
            return out


class Bridge(socketserver.BaseRequestHandler):
    def send_frame(self, frame):
        self.request.sendall(struct.pack(">H", len(frame)) + frame)

    def handle(self):
        host, port = self.server.speculos
        dev = socket.create_connection((host, port))
        mtu = self.server.mtu
        apdu, expected, index = b"", None, 0
        try:
            while True:
                (n,) = struct.unpack(">H", recv_exact(self.request, 2))
                frame = recv_exact(self.request, n)
                if frame[0] == 0x08:
                    self.send_frame(bytes([0x08, 0, 0, 0, 0, mtu]))
                    continue
                assert frame[0] == 0x05, "tag"
                (i,) = struct.unpack(">H", frame[1:3])
                assert i == index, "sequence"
                if i == 0:
                    (expected,) = struct.unpack(">H", frame[3:5])
                    apdu = frame[5:]
                else:
                    apdu += frame[3:]
                index += 1
                if len(apdu) < expected:
                    continue
                dev.sendall(struct.pack(">I", len(apdu)) + apdu)
                (size,) = struct.unpack(">I", recv_exact(dev, 4))
                answer = recv_exact(dev, size + 2)
                print(f"> {apdu.hex()}\n< {answer.hex()}", flush=True)
                for f in frames(answer, mtu):
                    self.send_frame(f)
                apdu, expected, index = b"", None, 0
        except ConnectionError:
            pass
        finally:
            dev.close()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--listen", type=int, required=True)
    p.add_argument("--speculos", default="127.0.0.1:9999")
    p.add_argument("--mtu", type=int, default=23)
    a = p.parse_args()
    server = Server(("127.0.0.1", a.listen), Bridge)
    host, port = a.speculos.rsplit(":", 1)
    server.speculos = (host, int(port))
    server.mtu = a.mtu
    server.serve_forever()


if __name__ == "__main__":
    main()
