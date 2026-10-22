#!/usr/bin/env python3
"""Hardware-free device simulator. Python 3.10+, standard library only."""
import argparse
import getpass
import json
import os
import threading
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description="Rent Gate device simulator; Ctrl+C to stop")
    parser.add_argument("--server", default="http://localhost:8080")
    parser.add_argument("--device-id", required=True)
    args = parser.parse_args()
    key = os.environ.get("RENT_GATE_DEVICE_KEY") or getpass.getpass("Device key: ")
    # Match the firmware's direct connection, ignoring desktop/system proxies.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = 0.0
    last_expiry = 0
    relay_on = False
    state_lock = threading.Lock()
    stop = threading.Event()

    def timer():
        nonlocal relay_on
        while not stop.wait(0.02):
            with state_lock:
                if relay_on and time.monotonic() >= deadline:
                    relay_on = False
                    print("[LOCAL TIMER] relay OFF (expired)", flush=True)

    worker = threading.Thread(target=timer, daemon=True)
    worker.start()
    print("Polling every 3s. Use the admin console to release/stop this device.")
    try:
        while True:
            sent = time.monotonic()
            with state_lock:
                body = json.dumps({"device_id": args.device_id, "relay_on": relay_on}).encode()
            request = urllib.request.Request(args.server.rstrip("/") + "/poll", body,
                                             {"Content-Type": "application/json", "X-Device-Key": key})
            try:
                with opener.open(request, timeout=1.5) as response:
                    data = json.loads(response.read(1024))
                with state_lock:
                    if data["action"] == "off":
                        if relay_on:
                            print("[SERVER] relay OFF", flush=True)
                        relay_on = False
                        last_expiry = 0
                    elif data["action"] == "on" and data["expire_ts"] != last_expiry:
                        seconds = max(0, data["remaining_seconds"] - (time.monotonic() - sent))
                        deadline = time.monotonic() + seconds
                        relay_on = seconds > 0
                        last_expiry = data["expire_ts"]
                        print(f"[SERVER] relay {'ON' if relay_on else 'OFF'}, remaining {seconds:.1f}s", flush=True)
            except (urllib.error.URLError, OSError, ValueError, KeyError) as error:
                # Do not print URLs/headers or credentials.
                print(f"[NETWORK] {type(error).__name__}; existing local countdown continues", flush=True)
            time.sleep(max(0, 3 - (time.monotonic() - sent)))
    except KeyboardInterrupt:
        print("\nSimulator stopped; relay OFF")
    finally:
        stop.set()
        worker.join(timeout=1)


if __name__ == "__main__":
    main()
