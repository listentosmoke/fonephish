import http from "node:http";
import https from "node:https";
import net from "node:net";

const listenHost = process.env.LISTEN_HOST || "127.0.0.1";
const listenPort = Number(process.env.LISTEN_PORT || "8090");
const targetUrl = new URL(process.env.TARGET_URL || "http://192.168.1.147:8080");
const webhookUrl = process.env.DISCORD_WEBHOOK_URL || "";
const startupTime = new Date().toISOString();

function getRequestIp(req) {
  const forwarded = req.headers["cf-connecting-ip"] || req.headers["x-forwarded-for"];
  if (typeof forwarded === "string" && forwarded.trim()) {
    return forwarded.split(",")[0].trim();
  }
  return req.socket.remoteAddress || "unknown";
}

function buildMessage(kind, req) {
  const ua = String(req.headers["user-agent"] || "unknown");
  const ip = getRequestIp(req);
  const host = String(req.headers.host || "unknown");
  const path = req.url || "/";
  const ray = String(req.headers["cf-ray"] || "n/a");
  const protocol = kind === "websocket" ? "WebSocket upgrade" : req.method || "HTTP";
  const ts = new Date().toISOString();
  return [
    `Brawlcup domain hit`,
    `Time: ${ts}`,
    `Type: ${protocol}`,
    `Host: ${host}`,
    `Path: ${path}`,
    `IP: ${ip}`,
    `CF-Ray: ${ray}`,
    `UA: ${ua}`,
  ].join("\n");
}

function logHit(kind, req) {
  const ip = getRequestIp(req);
  const path = req.url || "/";
  const host = String(req.headers.host || "unknown");
  console.log(`[proxy] ${kind} ${host}${path} from ${ip}`);
}

function postWebhook(message) {
  if (!webhookUrl) {
    console.warn(`[proxy] Webhook skipped because DISCORD_WEBHOOK_URL is not set.`);
    return;
  }

  try {
    const url = new URL(webhookUrl);
    const body = JSON.stringify({ content: message.slice(0, 1900) });
    const client = url.protocol === "https:" ? https : http;
    const req = client.request(
      {
        protocol: url.protocol,
        hostname: url.hostname,
        port: url.port || (url.protocol === "https:" ? 443 : 80),
        method: "POST",
        path: `${url.pathname}${url.search}`,
        headers: {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
        },
      },
      (res) => {
        res.resume();
        if (res.statusCode && res.statusCode >= 400) {
          console.warn(`[proxy] Discord webhook responded with ${res.statusCode}.`);
        }
      },
    );
    req.on("error", (error) => {
      console.warn(`[proxy] Discord webhook error: ${error.message}`);
    });
    req.end(body);
  } catch (error) {
    console.warn(`[proxy] Invalid Discord webhook URL: ${error.message}`);
  }
}

function createUpstreamRequestOptions(req) {
  const headers = { ...req.headers };
  headers.host = targetUrl.host;
  headers["x-forwarded-host"] = String(req.headers.host || "");
  headers["x-forwarded-proto"] = "https";

  return {
    protocol: targetUrl.protocol,
    hostname: targetUrl.hostname,
    port: targetUrl.port || (targetUrl.protocol === "https:" ? 443 : 80),
    method: req.method,
    path: req.url,
    headers,
  };
}

const server = http.createServer((req, res) => {
  logHit("http", req);
  const client = targetUrl.protocol === "https:" ? https : http;
  const upstream = client.request(createUpstreamRequestOptions(req), (upstreamRes) => {
    res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
    upstreamRes.pipe(res);
  });

  upstream.on("error", (error) => {
    res.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
    res.end(`Proxy upstream error: ${error.message}`);
  });

  req.pipe(upstream);
  postWebhook(buildMessage("http", req));
});

server.on("upgrade", (req, socket, head) => {
  logHit("websocket", req);
  const upstreamSocket = net.connect(
    Number(targetUrl.port || 80),
    targetUrl.hostname,
    () => {
      const headers = { ...req.headers };
      headers.host = targetUrl.host;
      headers["x-forwarded-host"] = String(req.headers.host || "");
      headers["x-forwarded-proto"] = "https";

      const lines = [`${req.method} ${req.url} HTTP/${req.httpVersion}`];
      for (const [key, value] of Object.entries(headers)) {
        if (Array.isArray(value)) {
          value.forEach((item) => lines.push(`${key}: ${item}`));
        } else if (typeof value !== "undefined") {
          lines.push(`${key}: ${value}`);
        }
      }
      upstreamSocket.write(`${lines.join("\r\n")}\r\n\r\n`);
      if (head.length > 0) {
        upstreamSocket.write(head);
      }
      socket.pipe(upstreamSocket).pipe(socket);
    },
  );

  upstreamSocket.on("error", (error) => {
    console.warn(`[proxy] WebSocket upstream error: ${error.message}`);
    socket.destroy();
  });

  socket.on("error", () => {
    upstreamSocket.destroy();
  });

  postWebhook(buildMessage("websocket", req));
});

server.listen(listenPort, listenHost, () => {
  console.log(
    `[proxy] Listening on http://${listenHost}:${listenPort} -> ${targetUrl.toString()} (started ${startupTime})`,
  );
});
