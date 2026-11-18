const ws = new WebSocket("wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket");

ws.onopen = () => {
  console.log("WS Opened");
};

ws.onmessage = (event) => {
  const msg = String(event.data);
  if (msg.startsWith("0")) {
    ws.send("40");
  } else if (msg === "2") {
    ws.send("3");
  } else if (msg.startsWith("42")) {
    try {
      const parsed = JSON.parse(msg.slice(2));
      const [eventName, data] = parsed;
      console.log(`\nEvent: ${eventName}`);
      console.log(JSON.stringify(data, null, 2));
    } catch (e) {
      console.log("Raw 42:", msg);
    }
  }
};

setTimeout(() => {
  ws.close();
  process.exit(0);
}, 6000);
