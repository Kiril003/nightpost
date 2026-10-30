"""Живий канал відповідає на ping. Запуск: MIGRATE.md, крок 6; очікуваний вивід {"t": "pong"}."""
import asyncio
import os

import websockets

URL = os.environ.get("WS_URL", "ws://127.0.0.1:8080/relay/v2/live")


async def main() -> None:
    async with websockets.connect(URL) as ws:
        await ws.send('{"op":"ping"}')
        print(await asyncio.wait_for(ws.recv(), 5))


asyncio.run(main())
