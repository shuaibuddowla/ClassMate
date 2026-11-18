const ws = new WebSocket("wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket");

ws.onopen = () => {
  console.log("WS Opened");
};

ws.onmessage = (event) => {
  const msg = String(event.data);
  console.log("WS Msg:", msg.slice(0, 150));
  if (msg.startsWith("0")) {
    console.log("Received handshake, sending 40");
    ws.send("40");
  } else if (msg === "2") {
    console.log("Received ping 2, sending pong 3");
    ws.send("3");
  }
};

ws.onerror = (err) => {
  console.error("WS Error:", err);
};

ws.onclose = () => {
  console.log("WS Closed");
};

setTimeout(() => {
  ws.close();
  process.exit(0);
}, 6000);
