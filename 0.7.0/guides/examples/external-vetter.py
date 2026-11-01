#!/usr/bin/env python3
"""A minimal external vetter for Skills Gateway. Standard library only.

Run it with `python3 external-vetter.py`; it listens on 8765 and answers the
POSTs the gateway's external vetting connector sends.
"""

import json
import os
import re
from http.server import BaseHTTPRequestHandler, HTTPServer

# One rule: a piped-to-shell install line, which a skill should never carry.
# Two linear scans rather than one pattern spanning the line: a pattern that
# scans from every "curl" to the pipe is quadratic on a line of repeated "curl ".
CURL = re.compile(r"\bcurl\s")
PIPE_TO_SHELL = re.compile(r"\|\s*(?:ba|z)?sh\b")


def pipes_curl_to_shell(line):
    curl = CURL.search(line)
    return curl is not None and PIPE_TO_SHELL.search(line, curl.end()) is not None


def review_with_model(_files):
    """Stub: where a model call would go, given the same file list as `review`.

    Returns findings in the same shape as the regex rule below.
    """
    # answer = client.messages.create(model=..., messages=[...])
    # return json.loads(answer.content[0].text)["findings"]
    return []


def review(files):
    findings = []
    for entry in files:
        if not entry.get("scanned") or not entry.get("content"):
            continue  # sent unscanned by the gateway: oversized or not UTF-8 text
        for lineno, line in enumerate(entry["content"].splitlines(), start=1):
            if pipes_curl_to_shell(line):
                findings.append({
                    "id": "curl-pipe-sh",
                    "severity": "high",
                    "location": f"{entry['path']}:{lineno}",
                    "message": "instructions pipe a downloaded script straight into a shell",
                })
    return findings + review_with_model(files)


def read_body(handler):
    """The gateway streams its request chunked; curl sends a Content-Length. Accept both."""
    if handler.headers.get("Transfer-Encoding", "").lower() == "chunked":
        body = b""
        while size := int(handler.rfile.readline().split(b";")[0], 16):
            body += handler.rfile.read(size)
            handler.rfile.readline()  # the CRLF closing each chunk
        handler.rfile.readline()  # the CRLF closing the last, empty chunk
        return body
    return handler.rfile.read(int(handler.headers["Content-Length"]))


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        request = json.loads(read_body(self))
        findings = review(request.get("files", []))
        body = json.dumps({
            "state": "fail" if findings else "pass",
            "reportUrl": None,
            "findings": findings,
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8765"))), Handler).serve_forever()
