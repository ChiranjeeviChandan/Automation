"""The update guard brings Jeevi back when an update leaves it gone or stopped.

Run: python tests/run.py update_guard

Context (owner report 2026-11-13): Jeevi answered 404 right after updates. Watchtower stops the old
container, removes it, then creates the new one. On a VPS with a slow disk the removal ran past the
10 minutes Watchtower gives an update from the button: Watchtower logged "context deadline exceeded",
gave up without creating the new container, and Docker finished the removal anyway. No Jeevi
container was left at all. Reproduced with Watchtower v1.24.0 against a real Docker daemon.

docker/guard.py runs beside Watchtower (service `guard` in both compose files) and recreates or
restarts Jeevi in exactly those states. A real Docker daemon is not available in CI, so this test
drives the guard against an in-memory Docker that answers the same API calls.
"""
from _paths import ROOT  # noqa: E402,F401  (also points tempfile at a per-run folder)
import ast
import copy
import http.server
import importlib.util
import io
import json
import os
import socketserver
import subprocess
import sys
import tempfile
import threading
import tokenize

import yaml

FAIL = []


def check(label, ok):
    print(("PASS" if ok else "FAIL") + ": " + label)
    if not ok:
        FAIL.append(label)


spec = importlib.util.spec_from_file_location("jeevi_guard", ROOT / "docker" / "guard.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
REAL_API = guard.api
guard.STORE = tempfile.mkdtemp()
guard.GRACE = 120
guard.log = lambda msg: None

OLD_IMAGE, NEW_IMAGE = "sha256:" + "1" * 64, "sha256:" + "2" * 64
REF = "ghcr.io/blogminhquy/javis-os:latest"
IMAGE_CONFIG = {   # what the old image itself sets (Dockerfile ENV, CMD, VOLUME, EXPOSE, LABEL)
    "Env": ["PATH=/usr/local/bin:/usr/bin", "JEEVI_PORT=7777", "JEEVI_CODEX_SANDBOX=off"],
    "Cmd": ["sh", "-c", "python -m uvicorn main:app"],
    "Entrypoint": ["/usr/bin/tini", "--", "/app/docker/entrypoint.sh"],
    "WorkingDir": "/app", "User": "jeevi",
    "Labels": {"org.opencontainers.image.title": "Jeevi OS", "org.opencontainers.image.version": "0.556.2"},
    "Volumes": {"/data": {}, "/brains": {}, "/home/jeevi/.claude": {}, "/home/jeevi/.codex": {}},
    "ExposedPorts": {"7777/tcp": {}},
    "Healthcheck": {"Test": ["CMD-SHELL", "python -c probe || exit 1"]},
}


def jeevi_inspect(cid="a" * 64, image=OLD_IMAGE, state="running", cmd=None):
    """A container the way Docker reports one created by docker-compose.multi.yml."""
    return {
        "Id": cid, "Name": "/jeevi", "Image": image,
        "State": {"Status": state},
        "Config": {
            "Hostname": cid[:12], "Image": REF, "MacAddress": "02:42:ac:11:00:02",
            "Env": ["WATCHTOWER_TOKEN=jeevi-update", "JEEVI_ADMIN_USER=admin"] + IMAGE_CONFIG["Env"],
            "Cmd": cmd or IMAGE_CONFIG["Cmd"], "Entrypoint": IMAGE_CONFIG["Entrypoint"],
            "WorkingDir": "/app", "User": "jeevi",
            "Labels": dict(IMAGE_CONFIG["Labels"], **{
                "com.docker.compose.project": "jeevi", "com.docker.compose.service": "jeevi",
                "com.docker.compose.image": image, "traefik.enable": "true"}),
            "Volumes": IMAGE_CONFIG["Volumes"], "ExposedPorts": {"7777/tcp": {}},
            "Healthcheck": {"Test": ["CMD", "python", "-c", "compose probe"], "Interval": 30000000000},
        },
        "HostConfig": {
            "NetworkMode": "jeevi_default",
            "RestartPolicy": {"Name": "unless-stopped"},
            "PortBindings": {"7777/tcp": [{"HostIp": "0.0.0.0", "HostPort": "7777"}]},
            "Binds": None,
            "Mounts": [{"Type": "volume", "Source": "jeevi_jeevi-data", "Target": "/data"},
                       {"Type": "volume", "Source": "jeevi_claude-auth", "Target": "/home/jeevi/.claude"},
                       {"Type": "volume", "Source": "jeevi_codex-auth", "Target": "/home/jeevi/.codex"}],
        },
        "Mounts": [
            {"Type": "volume", "Name": "jeevi_jeevi-data", "Destination": "/data", "RW": True},
            {"Type": "volume", "Name": "jeevi_claude-auth", "Destination": "/home/jeevi/.claude", "RW": True},
            {"Type": "volume", "Name": "jeevi_codex-auth", "Destination": "/home/jeevi/.codex", "RW": True},
            # /brains has no volume in this stack: Docker made an anonymous one from the image's VOLUME.
            {"Type": "volume", "Name": "f" * 64, "Destination": "/brains", "RW": True},
        ],
        "NetworkSettings": {"Networks": {
            "jeevi_default": {"Aliases": ["jeevi", cid[:12]], "IPAddress": "172.18.0.2",
                              "EndpointID": "e1", "NetworkID": "n1", "MacAddress": "02:42:ac:12:00:02"},
            "jeevi-web": {"Aliases": ["jeevi"], "IPAddress": "172.19.0.3", "EndpointID": "e2",
                          "NetworkID": "n2", "DriverOpts": {"x": "y"}},
        }},
    }


class FakeDocker:
    """The handful of Engine API calls the guard makes, kept in memory."""

    def __init__(self):
        self.containers = {}           # name -> inspect
        self.images = {REF: NEW_IMAGE, OLD_IMAGE: OLD_IMAGE, NEW_IMAGE: NEW_IMAGE}
        self.calls = []
        self.create_status = None      # force an answer for POST /containers/create
        self.counter = 0

    def by_ref(self, ref):
        for name, c in self.containers.items():
            if ref in (name, c["Id"]):
                return name, c
        return None, None

    def __call__(self, method, path, body=None, timeout=60):
        self.calls.append((method, path, copy.deepcopy(body)))
        route, _, query = path.partition("?")
        parts = route.strip("/").split("/")
        if method == "GET" and parts[0] == "containers":
            _, c = self.by_ref(parts[1])
            return (200, copy.deepcopy(c)) if c else (404, {"message": "No such container"})
        if method == "GET" and parts[0] == "images":
            ref = "/".join(parts[1:-1])
            if ref not in self.images:
                return 404, {"message": "No such image"}
            img = self.images[ref]
            return 200, {"Id": img, "Config": copy.deepcopy(IMAGE_CONFIG) if img == OLD_IMAGE else {}}
        if method == "POST" and route == "/containers/create":
            if self.create_status:
                status, self.create_status = self.create_status, None
                return status, {"message": "forced"}
            name = query.split("=", 1)[1]
            if name in self.containers:
                return 409, {"message": "Conflict. The container name is already in use"}
            if body["Image"] not in self.images:
                return 404, {"message": "No such image: " + body["Image"]}
            self.counter += 1
            cid = str(self.counter) * 64
            c = jeevi_inspect(cid=cid, image=self.images[body["Image"]], state="created")
            c["Config"] = {k: v for k, v in body.items() if k not in ("HostConfig", "NetworkingConfig")}
            c["HostConfig"] = body["HostConfig"]
            self.containers[name] = c
            return 201, {"Id": cid}
        if method == "POST" and parts[0] == "containers" and parts[-1] == "start":
            _, c = self.by_ref(parts[1])
            if not c:
                return 404, {"message": "No such container"}
            if c["State"]["Status"] == "running":
                return 304, None
            c["State"]["Status"] = "running"
            return 204, None
        if method == "DELETE" and parts[0] == "containers":
            name, _ = self.by_ref(parts[1])
            self.containers.pop(name, None)
            return 204, None
        if method == "POST" and parts[0] == "networks":
            return 200, None
        if method == "POST" and route == "/images/create":
            self.images[REF] = NEW_IMAGE
            return 200, '{"status":"Downloaded newer image"}'
        return 500, {"message": "unexpected call " + method + " " + path}

    def made(self, what):
        return [c for c in self.calls if c[0] == "POST" and c[1].startswith(what)]


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def new_guard(docker):
    guard.api = docker
    clock = Clock()
    return guard.Guard(now=clock), clock


def reset_store():
    for f in os.listdir(guard.STORE):
        os.unlink(os.path.join(guard.STORE, f))


# ------------------------------------------------------------------
# 1. The create body: what the old image set gives way to the new one
# ------------------------------------------------------------------
snap = {"container": jeevi_inspect(cmd=["sh", "-c", "JEEVI_DEPLOY_TARGET=hostinger exec uvicorn"]),
        "image": IMAGE_CONFIG}
body, extra = guard.create_spec(snap)
check("Env keeps what compose set and drops the old image's own values",
      body["Env"] == ["WATCHTOWER_TOKEN=jeevi-update", "JEEVI_ADMIN_USER=admin"])
check("a Cmd set by compose (Hostinger) is kept", body["Cmd"][2].startswith("JEEVI_DEPLOY_TARGET=hostinger"))
check("Entrypoint, WorkingDir and User from the old image are cleared",
      body["Entrypoint"] is None and body["WorkingDir"] == "" and body["User"] == "")
check("a compose healthcheck is kept", body["Healthcheck"]["Test"][3] == "compose probe")
check("the old image's labels go, compose and Traefik labels stay",
      "org.opencontainers.image.version" not in body["Labels"]
      and body["Labels"]["traefik.enable"] == "true" and body["Labels"]["com.docker.compose.project"] == "jeevi")
check("image VOLUME and EXPOSE entries are left to the new image",
      body["Volumes"] is None and body["ExposedPorts"] is None)
check("hostname named after the old container ID is cleared", body["Hostname"] == "")
check("no MAC address carried over", "MacAddress" not in body)
check("it is created from the image TAG (the version Watchtower pulled), not the old ID", body["Image"] == REF)
check("port binding and restart policy carried over",
      body["HostConfig"]["PortBindings"]["7777/tcp"][0]["HostPort"] == "7777"
      and body["HostConfig"]["RestartPolicy"]["Name"] == "unless-stopped")
check("the anonymous /brains volume is reattached by name, so its data comes back",
      body["HostConfig"]["Binds"] == ["f" * 64 + ":/brains"])
check("named volumes are not added twice", len(body["HostConfig"]["Mounts"]) == 3)
check("created on the network compose named first",
      list(body["NetworkingConfig"]["EndpointsConfig"]) == ["jeevi_default"])
ep = body["NetworkingConfig"]["EndpointsConfig"]["jeevi_default"]
check("aliases keep the service name and drop the old container ID", ep == {"Aliases": ["jeevi"]})
check("the second network (shared proxy) is connected after create",
      extra == {"jeevi-web": {"Aliases": ["jeevi"], "DriverOpts": {"x": "y"}}})

ro = copy.deepcopy(snap)
ro["container"]["Mounts"][3]["RW"] = False
check("a read-only anonymous volume stays read-only",
      guard.create_spec(ro)[0]["HostConfig"]["Binds"] == ["f" * 64 + ":/brains:ro"])
hn = copy.deepcopy(snap)
hn["container"]["Config"]["Hostname"] = "my-jeevi"
check("a hostname the owner chose is kept", guard.create_spec(hn)[0]["Hostname"] == "my-jeevi")
hm = copy.deepcopy(snap)
hm["container"]["HostConfig"]["NetworkMode"] = "host"
b, e = guard.create_spec(hm)
check("host network mode: no endpoint settings at all", "NetworkingConfig" not in b and e == {})
noimg = guard.create_spec({"container": snap["container"], "image": None})[0]
check("without the old image's config nothing is dropped (safe fallback)",
      "JEEVI_PORT=7777" in noimg["Env"])
check("create_spec leaves the snapshot itself untouched",
      snap["container"]["Config"]["Hostname"] == "a" * 12 and snap["container"]["HostConfig"]["Binds"] is None)

# ------------------------------------------------------------------
# 2. The incident: an update removed Jeevi and never created the new one
# ------------------------------------------------------------------
reset_store()
d = FakeDocker()
d.containers["jeevi"] = jeevi_inspect()
d.images[REF] = OLD_IMAGE
g, clock = new_guard(d)
check("a running Jeevi is fine", g.check() == "ok")
check("its snapshot is saved to the guard volume", os.path.exists(os.path.join(guard.STORE, "jeevi.json")))
d.images[REF] = NEW_IMAGE           # Watchtower pulled the new version...
del d.containers["jeevi"]           # ...removed the old container, timed out, and gave up
check("gone for less than the grace period: wait (Watchtower may still be creating it)", g.check() == "waiting")
clock.t += 60
check("still waiting at 60 s", g.check() == "waiting" and not d.made("/containers/create"))
clock.t += 61
check("gone for 2 minutes: recreated", g.check() == "recreated")
check("Jeevi runs again", d.containers["jeevi"]["State"]["Status"] == "running")
check("on the NEW version Watchtower already pulled", d.containers["jeevi"]["Image"] == NEW_IMAGE)
check("under the same name", d.made("/containers/create")[0][1] == "/containers/create?name=jeevi")
check("compose's image label points at the new image (no needless recreate on the next `up`)",
      d.containers["jeevi"]["Config"]["Labels"]["com.docker.compose.image"] == NEW_IMAGE)
check("joined the second network", len(d.made("/networks/jeevi-web/connect")) == 1)
check("the new container is watched from now on", g.check() == "ok" and g.snap["container"]["Id"] == "1" * 64)

# Another guard (after a restart of the guard itself) reads the snapshot from its volume.
del d.containers["jeevi"]
g2, clock2 = new_guard(d)
clock2.t += 1
g2.check()
clock2.t += 121
check("a restarted guard still has the snapshot and recreates Jeevi", g2.check() == "recreated")

# ------------------------------------------------------------------
# 3. Edge cases of recreating
# ------------------------------------------------------------------
reset_store()
d = FakeDocker()
d.containers["jeevi"] = jeevi_inspect()
g, clock = new_guard(d)
g.check()
del d.containers["jeevi"]
del d.images[REF]                   # the tag is not on this machine any more
g.check()
clock.t += 121
check("image missing: pulled, then created", g.check() == "recreated" and d.made("/images/create"))
check("pull asks for the repository and the tag separately",
      d.made("/images/create")[0][1] == "/images/create?fromImage=ghcr.io%2Fblogminhquy%2Fjavis-os&tag=latest")

del d.containers["jeevi"]
g.check()
clock.t += 121
d.create_status = 409               # Watchtower created it in the same second
check("name taken meanwhile: leaves it to whoever made it, starts nothing", g.check() == "failed")
check("the grace period starts over after an attempt", g.since is None)

reset_store()
d = FakeDocker()
g, clock = new_guard(d)
check("never saw Jeevi run, so no snapshot: does nothing", g.check() == "no-snapshot")
clock.t += 500
check("...even much later", g.check() == "no-snapshot" and not d.made("/containers/create"))

# ------------------------------------------------------------------
# 4. Stopped: by the owner (leave alone) or by a broken update (start again)
# ------------------------------------------------------------------
reset_store()
d = FakeDocker()
d.containers["jeevi"] = jeevi_inspect()
d.images[REF] = OLD_IMAGE
g, clock = new_guard(d)
g.check()
d.containers["jeevi"]["State"]["Status"] = "exited"     # the owner pressed Stop in Docker Manager
check("stopped by the owner (tag still on its image): left alone", g.check() == "owner-stopped")
clock.t += 3600
d.images[REF] = NEW_IMAGE           # even if a new image arrives later
check("...an hour later, with a newer image around, still left alone", g.check() == "owner-stopped")
g3, clock3 = new_guard(d)
check("...and a restarted guard remembers it", g3.check() == "owner-stopped")
check("nothing was started", not d.made("/containers/" + "a" * 64 + "/start"))
d.containers["jeevi"]["State"]["Status"] = "running"    # the owner starts it again
check("running again: watched as usual", g3.check() == "ok" and not g3.snap.get("owner_stopped"))

reset_store()
d = FakeDocker()
d.containers["jeevi"] = jeevi_inspect()
d.images[REF] = OLD_IMAGE
g, clock = new_guard(d)
g.check()
d.images[REF] = NEW_IMAGE                               # Watchtower pulled, then...
d.containers["jeevi"]["State"]["Status"] = "exited"     # ...the stop failed half way and it gave up
check("stopped by an update: waits first", g.check() == "waiting")
clock.t += 121
check("then starts the old container again (old version comes back)", g.check() == "started")
check("the same container, no removal", d.containers["jeevi"]["Id"] == "a" * 64
      and d.containers["jeevi"]["State"]["Status"] == "running")

reset_store()
d = FakeDocker()
d.containers["jeevi"] = jeevi_inspect()
g, clock = new_guard(d)
g.check()
d.containers["jeevi"]["State"]["Status"] = "dead"       # a removal that broke half way
g.check()
clock.t += 121
check("dead: removed and created again", g.check() == "recreated"
      and d.containers["jeevi"]["Id"] == "1" * 64 and d.containers["jeevi"]["State"]["Status"] == "running")

reset_store()
d = FakeDocker()
d.containers["jeevi"] = jeevi_inspect()
g, clock = new_guard(d)
g.check()
d.containers["jeevi"] = jeevi_inspect(cid="b" * 64, image=NEW_IMAGE, state="created")   # created, start failed
check("created but not started: waits first", g.check() == "waiting")
clock.t += 121
check("then starts it", g.check() == "started" and d.containers["jeevi"]["State"]["Status"] == "running")

for state in ("restarting", "removing", "paused"):
    d.containers["jeevi"]["State"]["Status"] = state
    clock.t += 500
    check(f"{state}: never touched", g.check() == "ok")

# A normal update: the old one goes and the new one runs within seconds. The guard only follows.
reset_store()
d = FakeDocker()
d.containers["jeevi"] = jeevi_inspect()
g, clock = new_guard(d)
g.check()
d.containers["jeevi"]["State"]["Status"] = "exited"
d.images[REF] = NEW_IMAGE
g.check()
clock.t += 3
del d.containers["jeevi"]
g.check()
clock.t += 3
d.containers["jeevi"] = jeevi_inspect(cid="c" * 64, image=NEW_IMAGE)
check("normal update: the guard creates and starts nothing", g.check() == "ok"
      and not d.made("/containers/create") and not [c for c in d.calls if c[1].endswith("/start")])
check("and watches the new container", g.snap["container"]["Id"] == "c" * 64)

# ------------------------------------------------------------------
# 5. The real transport: HTTP over the Docker unix socket
# ------------------------------------------------------------------
sock_dir = tempfile.mkdtemp()
sock_path = os.path.join(sock_dir, "docker.sock")
seen = []


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        data = json.dumps({"Id": "x", "State": {"Status": "running"}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        seen.append((self.path, json.loads(self.rfile.read(n)) if n else None))
        self.send_response(204)
        self.end_headers()


class UnixServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True


server = UnixServer(sock_path, Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
guard.api, guard.SOCKET = REAL_API, sock_path
status, body = guard.api("GET", "/containers/jeevi/json")
check("talks HTTP over the unix socket and decodes JSON", status == 200 and body["State"]["Status"] == "running")
status, body = guard.api("POST", "/containers/x/start", {"a": 1})
check("POST sends a JSON body; an empty 204 answer decodes to None", status == 204 and body is None
      and seen == [("/containers/x/start", {"a": 1})])
server.shutdown()

# ------------------------------------------------------------------
# 6. Shipped inside both compose files, the way Hostinger receives it
# ------------------------------------------------------------------
source = (ROOT / "docker" / "guard.py").read_text(encoding="utf-8")
check("guard.py has no '$' (compose would read it as a variable)", "$" not in source)
r = subprocess.run([sys.executable, str(ROOT / "tools" / "sync_guard.py"), "--check"], capture_output=True, text=True)
check("both compose files carry the current guard.py (else: python tools/sync_guard.py)", r.returncode == 0)

# The compose files carry a compact copy (hPanel refuses a compose over 32,768 characters, owner
# report 2026-11-14). It must be the very same program: same syntax tree once docstrings are gone.
sync_spec = importlib.util.spec_from_file_location("sync_guard", ROOT / "tools" / "sync_guard.py")
sync_guard = importlib.util.module_from_spec(sync_spec)
sync_spec.loader.exec_module(sync_guard)
compact = sync_guard.compact(source)


def without_docstrings(code):
    tree = ast.parse(code)
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and node.body:
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                node.body = node.body[1:]
    return ast.dump(tree)


check("the compact copy is the same program as guard.py", without_docstrings(source) == ast.dump(ast.parse(compact)))
compact_tokens = list(tokenize.generate_tokens(io.StringIO(compact).readline))
check("the compact copy drops docstrings, comments and blank lines",
      '"""' not in compact and "\n\n" not in compact
      and not any(tok.type == tokenize.COMMENT for tok in compact_tokens))
check("the compact copy is much smaller than guard.py", len(compact) < len(source) * 0.8)
check("the compact copy has no '$' either", "$" not in compact)
check("compact() keeps a '#' inside a string and cuts a real comment",
      sync_guard.compact('x = "a#b"  # note\nif x:\n    y = 1\n') == 'x = "a#b"\nif x:\n  y = 1\n')
for name in ("docker-compose.yml", "docker-compose.hostinger.yml"):
    services = yaml.safe_load((ROOT / name).read_text(encoding="utf-8"))["services"]
    svc = services.get("guard") or {}
    check(f"{name}: has the guard service", bool(svc))
    check(f"{name}: guard runs the compact copy of guard.py", svc.get("command") == [compact]
          and svc.get("entrypoint") == ["python", "-u", "-c"])
    check(f"{name}: guard has no network at all", svc.get("network_mode") == "none")
    check(f"{name}: guard reaches the Docker socket and keeps its snapshot on a volume",
          "/var/run/docker.sock:/var/run/docker.sock" in svc.get("volumes", [])
          and "guard-data:/guard" in svc.get("volumes", []))
    check(f"{name}: guard watches THIS stack's Jeevi", svc.get("environment", {}).get("JEEVI_NAME") == "${JEEVI_NAME:-jeevi}"
          and svc.get("container_name") == "${JEEVI_NAME:-jeevi}-guard")
    check(f"{name}: guard comes back after a reboot", svc.get("restart") == "unless-stopped")
    check(f"{name}: guard is stopped before Jeevi on `down`", svc.get("depends_on") == ["jeevi"])
    # Watchtower must never update the guard: it watches only the Jeevi container by exact name.
    check(f"{name}: Watchtower watches Jeevi only", str(services["watchtower"]["command"]).strip() == "${JEEVI_NAME:-jeevi}")
    wt_env = services["watchtower"]["environment"]
    check(f"{name}: an update from the button gets 30 minutes, not Watchtower's 10",
          wt_env.get("WATCHTOWER_HTTP_API_UPDATE_TIMEOUT") == "30m")

if FAIL:
    print("\nFAILED:", ", ".join(FAIL))
    sys.exit(1)
print("\nOK - test_update_guard: all passed")
