import asyncio
import importlib.util
from pathlib import Path
import socket
import unittest


SPEC = importlib.util.spec_from_file_location(
    "channel_file_bridge", Path(__file__).resolve().parents[1] / "bridge-channel-file-probe.py"
)
BRIDGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BRIDGE)


class BufferWriter:
    def __init__(self):
        self.messages = asyncio.Queue()

    def write(self, payload):
        self.messages.put_nowait(payload)

    async def drain(self):
        pass


class UsbFixtureBridgeTest(unittest.IsolatedAsyncioTestCase):
    async def test_preserves_datagram_boundaries_in_both_directions(self):
        loop = asyncio.get_running_loop()
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as tunnel_udp, \
                socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server_udp:
            for endpoint in (tunnel_udp, server_udp):
                endpoint.bind(("127.0.0.1", 0))
                endpoint.setblocking(False)
            tunnel_udp.connect(server_udp.getsockname())
            server_udp.connect(tunnel_udp.getsockname())
            reader, writer = asyncio.StreamReader(), BufferWriter()
            relay_sender = asyncio.create_task(BRIDGE.stream_to_datagrams(reader, tunnel_udp))
            relay_receiver = asyncio.create_task(BRIDGE.datagrams_to_stream(tunnel_udp, writer))
            try:
                reader.feed_data(b"\x00\x03one\x00\x03two")
                for expected in (b"one", b"two"):
                    datagram = await asyncio.wait_for(loop.sock_recv(server_udp, 100), 2)
                    self.assertEqual(datagram, expected)
                    await loop.sock_sendall(server_udp, expected.upper())
                    self.assertEqual(await asyncio.wait_for(writer.messages.get(), 2), b"\x00\x03" + expected.upper())
            finally:
                for task in (relay_sender, relay_receiver):
                    task.cancel()
                await asyncio.gather(relay_sender, relay_receiver, return_exceptions=True)

    async def test_rejects_invalid_lengths_before_udp_io(self):
        for size in (0, BRIDGE.MAX_PACKET + 1):
            with self.subTest(size=size):
                reader = asyncio.StreamReader()
                reader.feed_data(size.to_bytes(2, "big"))
                with self.assertRaisesRegex(ValueError, "invalid fixture datagram length"):
                    await asyncio.wait_for(BRIDGE.stream_to_datagrams(reader, None), 2)
