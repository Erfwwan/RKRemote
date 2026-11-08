import { DurableObject } from "cloudflare:workers";

export interface Env {
  SIGNAL_ROOMS: DurableObjectNamespace;
}

const ROOM_ID = /^[a-f0-9]{64}$/;
const ROLES = new Set(["target", "controller"]);

function jsonError(status: number, error: string): Response {
  return Response.json({ ok: false, error }, { status });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const match = url.pathname.match(/^\/signal\/([a-f0-9]{64})$/);
    if (!match) return jsonError(404, "not_found");
    if (request.method !== "GET") return jsonError(405, "method_not_allowed");
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return jsonError(426, "websocket_required");
    }

    const roomId = match[1];
    const role = url.searchParams.get("role") ?? "";
    if (!ROOM_ID.test(roomId) || !ROLES.has(role)) {
      return jsonError(400, "invalid_room_or_role");
    }

    const id = env.SIGNAL_ROOMS.idFromName(roomId);
    return env.SIGNAL_ROOMS.get(id).fetch(request);
  },
};

type Role = "target" | "controller";
type SocketMeta = { role: Role; connectedAt: number };

const ALLOWED_TYPES = new Set(["offer", "answer", "ice", "ready", "reject", "bye"]);
const MAX_MESSAGE_BYTES = 12 * 1024;
const ROOM_TTL_MS = 10 * 60 * 1000;

export class SignalRoom extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket required", { status: 426 });
    }

    const role = new URL(request.url).searchParams.get("role") as Role | null;
    if (role !== "target" && role !== "controller") {
      return new Response("Invalid role", { status: 400 });
    }

    const sockets = this.ctx.getWebSockets();
    const existingRoles = sockets
      .map((socket) => (socket.deserializeAttachment() as SocketMeta | null)?.role)
      .filter(Boolean);

    if (existingRoles.includes(role)) {
      return new Response("Role already connected", { status: 409 });
    }
    if (role === "controller" && !existingRoles.includes("target")) {
      return new Response("Target must connect first", { status: 409 });
    }
    if (sockets.length >= 2) return new Response("Room is full", { status: 409 });

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ role, connectedAt: Date.now() } satisfies SocketMeta);

    if (sockets.length === 0) {
      await this.ctx.storage.setAlarm(Date.now() + ROOM_TTL_MS);
    } else {
      for (const peer of sockets) {
        if (peer.readyState === WebSocket.OPEN) {
          peer.send(JSON.stringify({ v: 1, type: "peer-joined", payload: { role } }));
        }
      }
      server.send(JSON.stringify({ v: 1, type: "peer-joined", payload: { role: "target" } }));
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string" || new TextEncoder().encode(message).byteLength > MAX_MESSAGE_BYTES) {
      socket.close(1009, "Invalid or oversized message");
      return;
    }

    let parsed: { v?: number; type?: string; payload?: unknown };
    try {
      parsed = JSON.parse(message);
    } catch {
      socket.close(1007, "Invalid JSON");
      return;
    }

    if (parsed.v !== 1 || typeof parsed.type !== "string" || !ALLOWED_TYPES.has(parsed.type)) {
      socket.close(1008, "Unsupported signaling message");
      return;
    }

    const peers = this.ctx.getWebSockets().filter((peer) => peer !== socket && peer.readyState === WebSocket.OPEN);
    if (peers.length !== 1) {
      socket.send(JSON.stringify({ v: 1, type: "peer-not-ready", payload: {} }));
      return;
    }

    const forwarded = JSON.stringify({
      v: 1,
      type: parsed.type,
      payload: parsed.payload ?? {},
    });
    peers[0].send(forwarded);
  }

  async webSocketClose(socket: WebSocket, code: number, reason: string): Promise<void> {
    this.notifyPeer(socket, "peer-left");
    try { socket.close(code, reason); } catch { /* already closed */ }
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    this.notifyPeer(socket, "peer-left");
    try { socket.close(1011, "WebSocket error"); } catch { /* already closed */ }
  }

  async alarm(): Promise<void> {
    for (const socket of this.ctx.getWebSockets()) {
      try { socket.close(1001, "Signaling room expired"); } catch { /* ignore */ }
    }
    await this.ctx.storage.deleteAll();
  }

  private notifyPeer(socket: WebSocket, type: string): void {
    for (const peer of this.ctx.getWebSockets()) {
      if (peer !== socket && peer.readyState === WebSocket.OPEN) {
        try { peer.send(JSON.stringify({ v: 1, type, payload: {} })); } catch { /* ignore */ }
      }
    }
  }
}
