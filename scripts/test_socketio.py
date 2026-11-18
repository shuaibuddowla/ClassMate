import asyncio
import ssl
import json

try:
    import websockets
except ImportError:
    import os
    os.system("pip install websockets")
    import websockets

async def test_ws():
    uri = "wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket"
    ssl_context = ssl.create_default_context()
    ssl_context.check_hostname = False
    ssl_context.verify_mode = ssl.CERT_NONE

    print("Connecting to:", uri)
    try:
        async with websockets.connect(uri, ssl=ssl_context) as ws:
            print("Connected! Waiting for handshake...")
            init_msg = await asyncio.wait_for(ws.recv(), timeout=5)
            print("Received handshake:", init_msg)
            # Socket.IO client sends "40" to join default namespace
            await ws.send("40")
            print("Sent 40")
            
            # Wait for next messages
            for _ in range(5):
                msg = await asyncio.wait_for(ws.recv(), timeout=5)
                print("Received:", msg[:200])
                if msg == "2":
                    await ws.send("3")
                    print("Sent ping response 3")
    except Exception as e:
        print("WS Error:", e)

asyncio.run(test_ws())
