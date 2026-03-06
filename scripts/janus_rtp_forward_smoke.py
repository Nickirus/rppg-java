#!/usr/bin/env python3
"""
Start Janus VideoRoom rtp_forward for a live publisher and verify UDP packet flow.

Example:
  python scripts/janus_rtp_forward_smoke.py ^
    --janus-url http://localhost:8088/janus ^
    --room 1234 ^
    --host host.docker.internal ^
    --audio-port 5002 ^
    --video-port 5004 ^
    --duration 15
"""

from __future__ import annotations

import argparse
import json
import random
import select
import socket
import string
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


def tx_id() -> str:
    alphabet = string.ascii_lowercase + string.digits
    return "".join(random.choice(alphabet) for _ in range(12))


def now_ms() -> int:
    return int(time.time() * 1000)


def pretty(obj: Any) -> str:
    return json.dumps(obj, indent=2, ensure_ascii=False)


class JanusError(RuntimeError):
    pass


class JanusClient:
    def __init__(self, janus_url: str, timeout_sec: float = 8.0):
        self.base = janus_url.rstrip("/")
        self.timeout_sec = timeout_sec

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base}{path}",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.URLError as exc:
            raise JanusError(f"HTTP POST failed for {path}: {exc}") from exc

    def _get(self, path: str, query: dict[str, Any] | None = None) -> dict[str, Any]:
        query = query or {}
        qs = urllib.parse.urlencode(query)
        url = f"{self.base}{path}"
        if qs:
            url = f"{url}?{qs}"
        req = urllib.request.Request(url, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if isinstance(data, list):
                    if not data:
                        return {"janus": "keepalive"}
                    return data[0]
                return data
        except urllib.error.URLError as exc:
            raise JanusError(f"HTTP GET failed for {path}: {exc}") from exc

    @staticmethod
    def _check_error(message: dict[str, Any]) -> None:
        if message.get("janus") == "error":
            err = message.get("error", {})
            raise JanusError(f"Janus error {err.get('code')}: {err.get('reason')}")

    def request(
        self,
        path: str,
        payload: dict[str, Any],
        session_id: int | None = None,
        wait_timeout_sec: float = 12.0,
    ) -> dict[str, Any]:
        transaction = tx_id()
        body = dict(payload)
        body["transaction"] = transaction

        immediate = self._post(path, body)
        self._check_error(immediate)

        if immediate.get("transaction") == transaction and immediate.get("janus") != "ack":
            return immediate

        if session_id is None:
            if immediate.get("transaction") == transaction:
                return immediate
            raise JanusError("Request returned ack but session_id is unknown for event polling")

        deadline = time.time() + wait_timeout_sec
        while time.time() < deadline:
            event = self._get(f"/{session_id}", {"rid": now_ms(), "maxev": 1})
            self._check_error(event)
            if event.get("transaction") == transaction:
                return event
        raise JanusError(f"Timeout waiting Janus event for transaction={transaction}")


@dataclass
class UdpCounterResult:
    packets: int = 0
    bytes_total: int = 0


def count_udp_packets(ports: list[int], duration_sec: float) -> dict[int, UdpCounterResult]:
    sockets: list[socket.socket] = []
    port_map: dict[socket.socket, int] = {}
    stats: dict[int, UdpCounterResult] = {p: UdpCounterResult() for p in ports}
    try:
        for port in ports:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("0.0.0.0", port))
            s.setblocking(False)
            sockets.append(s)
            port_map[s] = port

        end_at = time.time() + duration_sec
        while time.time() < end_at:
            timeout = max(0.0, min(0.5, end_at - time.time()))
            ready, _, _ = select.select(sockets, [], [], timeout)
            for s in ready:
                try:
                    data, _ = s.recvfrom(65535)
                except OSError:
                    continue
                port = port_map[s]
                stats[port].packets += 1
                stats[port].bytes_total += len(data)
        return stats
    finally:
        for s in sockets:
            try:
                s.close()
            except OSError:
                pass


def choose_publisher(participants: list[dict[str, Any]], explicit_id: int | None) -> int:
    if explicit_id is not None:
        for p in participants:
            if int(p.get("id", -1)) == explicit_id:
                return explicit_id
        raise JanusError(f"Publisher id={explicit_id} not found in participants list")

    if not participants:
        raise JanusError("No active publishers in room. Start publisher page first.")
    return int(participants[0]["id"])


def main() -> int:
    parser = argparse.ArgumentParser(description="Janus VideoRoom rtp_forward smoke test")
    parser.add_argument("--janus-url", default="http://localhost:8088/janus")
    parser.add_argument("--room", type=int, default=1234)
    parser.add_argument("--publisher-id", type=int, default=None)
    parser.add_argument("--host", default="host.docker.internal")
    parser.add_argument("--audio-port", type=int, default=5002)
    parser.add_argument("--video-port", type=int, default=5004)
    parser.add_argument("--duration", type=float, default=15.0)
    parser.add_argument("--admin-key", default=None, help="Optional VideoRoom admin_key")
    args = parser.parse_args()

    client = JanusClient(args.janus_url)
    session_id: int | None = None
    handle_id: int | None = None
    stream_id: int | None = None

    try:
        print(f"[1/6] Creating Janus session via {args.janus_url}")
        created = client.request("", {"janus": "create"})
        session_id = int(created["data"]["id"])
        print(f"  session_id={session_id}")

        print("[2/6] Attaching VideoRoom plugin handle")
        attached = client.request(
            f"/{session_id}",
            {"janus": "attach", "plugin": "janus.plugin.videoroom"},
            session_id=session_id,
        )
        handle_id = int(attached["data"]["id"])
        print(f"  handle_id={handle_id}")

        print(f"[3/6] Listing participants in room={args.room}")
        listed = client.request(
            f"/{session_id}/{handle_id}",
            {"janus": "message", "body": {"request": "listparticipants", "room": args.room}},
            session_id=session_id,
        )
        pdata = listed.get("plugindata", {}).get("data", {})
        participants = pdata.get("participants", [])
        print(f"  participants={len(participants)}")
        if participants:
            print(pretty(participants))

        publisher_id = choose_publisher(participants, args.publisher_id)
        print(f"[4/6] Using publisher_id={publisher_id}")

        print(
            f"[5/6] Starting rtp_forward -> host={args.host}, "
            f"audio_port={args.audio_port}, video_port={args.video_port}"
        )
        body: dict[str, Any] = {
            "request": "rtp_forward",
            "room": args.room,
            "publisher_id": publisher_id,
            "host": args.host,
            "audio_port": args.audio_port,
            "video_port": args.video_port,
        }
        if args.admin_key:
            body["admin_key"] = args.admin_key

        started = client.request(
            f"/{session_id}/{handle_id}",
            {"janus": "message", "body": body},
            session_id=session_id,
        )
        pdata2 = started.get("plugindata", {}).get("data", {})
        if pdata2.get("videoroom") != "rtp_forward":
            raise JanusError(f"Unexpected rtp_forward response: {pretty(started)}")
        stream_id = int(pdata2["stream_id"])
        print(f"  stream_id={stream_id}")

        print(f"[6/6] Counting UDP packets for {args.duration:.1f}s...")
        stats = count_udp_packets([args.audio_port, args.video_port], args.duration)
        for port in [args.audio_port, args.video_port]:
            st = stats[port]
            print(f"  port {port}: packets={st.packets}, bytes={st.bytes_total}")

        if stats[args.audio_port].packets == 0 and stats[args.video_port].packets == 0:
            raise JanusError(
                "No RTP packets observed on both ports. "
                "Check publisher is active and host/ports/firewall settings."
            )

        print("Smoke test OK: RTP packets observed.")
        return 0
    except JanusError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    finally:
        if session_id and handle_id and stream_id:
            try:
                stop_body: dict[str, Any] = {
                    "request": "stop_rtp_forward",
                    "room": args.room,
                    "stream_id": stream_id,
                }
                if args.admin_key:
                    stop_body["admin_key"] = args.admin_key
                client.request(
                    f"/{session_id}/{handle_id}",
                    {"janus": "message", "body": stop_body},
                    session_id=session_id,
                )
                print(f"Stopped rtp_forward stream_id={stream_id}")
            except Exception as exc:
                print(f"WARN: failed to stop_rtp_forward: {exc}", file=sys.stderr)

        if session_id and handle_id:
            try:
                client.request(
                    f"/{session_id}",
                    {"janus": "detach", "handle_id": handle_id},
                    session_id=session_id,
                )
            except Exception:
                pass
        if session_id:
            try:
                client.request(f"/{session_id}", {"janus": "destroy"}, session_id=session_id)
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())
