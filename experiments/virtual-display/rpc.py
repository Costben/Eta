#!/usr/bin/env python3
"""Send one bounded JSON request to the USB-forwarded probe socket."""
import json
import socket
import sys

request = json.loads(sys.argv[1]) if len(sys.argv) > 1 else {"action": "status"}
with socket.create_connection(("127.0.0.1", 18765), timeout=15) as connection:
    connection.sendall((json.dumps(request, ensure_ascii=False) + "\n").encode())
    result = connection.makefile("r", encoding="utf-8").readline()
    if not result:
        raise SystemExit("EMPTY_RESPONSE")
    value = json.loads(result)
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")))
    raise SystemExit(0 if value.get("ok") else 1)
