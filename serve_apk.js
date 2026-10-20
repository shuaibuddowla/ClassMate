const http = require("http");
const fs = require("fs");
const path = require("path");
const os = require("os");

const apkPath = path.resolve(__dirname, "app/build/outputs/apk/debug/app-debug.apk");

function getLocalIPs() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === "IPv4" && !iface.internal) {
        addresses.push(iface.address);
      }
    }
  }
  return addresses;
}

const server = http.createServer((req, res) => {
  console.log(`[${new Date().toLocaleTimeString()}] ${req.method} ${req.url} from ${req.socket.remoteAddress}`);

  if (req.url === "/app-debug.apk" || req.url === "/download" || req.url === "/" || req.url.startsWith("/download")) {
    if (!fs.existsSync(apkPath)) {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("APK file not found on disk. Build may still be in progress.");
      return;
    }

    const stat = fs.statSync(apkPath);
    res.writeHead(200, {
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Length": stat.size,
      "Content-Disposition": 'attachment; filename="ClassMate-debug.apk"',
      "Cache-Control": "no-cache, no-store, must-revalidate",
    });

    const stream = fs.createReadStream(apkPath);
    stream.pipe(res);
  } else {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("Not Found. Use /download to get the APK.");
  }
});

const PORT = 8080;
server.listen(PORT, "0.0.0.0", () => {
  console.log(`\n========================================`);
  console.log(`APK Download Server running on port ${PORT}`);
  const ips = getLocalIPs();
  ips.forEach((ip) => {
    console.log(` -> http://${ip}:${PORT}/download`);
  });
  console.log(` -> http://localhost:${PORT}/download`);
  console.log(`========================================\n`);
});
