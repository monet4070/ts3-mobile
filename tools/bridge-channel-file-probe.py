"""Forward only loopback TS3 UDP through two USB fixture streams; never use on a public interface."""

import argparse
import asyncio
import socket


LOOPBACK = "127.0.0.1"
MAX_PACKET = 4096


async def stream_to_datagrams(reader, udp):
    loop = asyncio.get_running_loop()
    while True:
        size = int.from_bytes(await reader.readexactly(2), "big")
        if not 0 < size <= MAX_PACKET:
            raise ValueError("invalid fixture datagram length")
        await loop.sock_sendall(udp, await reader.readexactly(size))


async def datagrams_to_stream(udp, writer):
    loop = asyncio.get_running_loop()
    while True:
        packet = await loop.sock_recv(udp, MAX_PACKET + 1)
        if not 0 < len(packet) <= MAX_PACKET:
            raise ValueError("invalid server fixture datagram length")
        writer.write(len(packet).to_bytes(2, "big") + packet)
        await writer.drain()


async def run(bridge_port, voice_port):
    active = set()

    async def relay(reader, writer):
        if len(active) >= 2:
            writer.close()
            await writer.wait_closed()
            return
        connection = asyncio.current_task()
        active.add(connection)
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                udp.bind((LOOPBACK, 0))
                udp.connect((LOOPBACK, voice_port))
                udp.setblocking(False)
                tasks = [
                    asyncio.create_task(stream_to_datagrams(reader, udp)),
                    asyncio.create_task(datagrams_to_stream(udp, writer)),
                ]
                try:
                    done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                    for task in done:
                        task.result()
                except asyncio.IncompleteReadError as error:
                    if error.partial:
                        print("USB fixture bridge closed during a datagram", flush=True)
                except (OSError, ValueError) as error:
                    print(f"USB fixture bridge failed: {type(error).__name__}", flush=True)
                finally:
                    for task in tasks:
                        task.cancel()
                    await asyncio.gather(*tasks, return_exceptions=True)
        finally:
            active.remove(connection)
            writer.close()
            await writer.wait_closed()

    async with await asyncio.start_server(relay, LOOPBACK, bridge_port, family=socket.AF_INET) as server:
        print("USB fixture bridge ready", flush=True)
        await server.serve_forever()


def main():
    def port(value):
        parsed = int(value)
        if not 0 < parsed < 65536:
            raise argparse.ArgumentTypeError("expected a port from 1 to 65535")
        return parsed

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge-port", required=True, type=port, metavar="PORT")
    parser.add_argument("--voice-port", required=True, type=port, metavar="PORT")
    args = parser.parse_args()
    try:
        asyncio.run(run(args.bridge_port, args.voice_port))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
