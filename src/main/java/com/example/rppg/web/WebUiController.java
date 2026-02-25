package com.example.rppg.web;

import com.example.rppg.app.RppgSnapshot;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class WebUiController {
    private final WebUiStateService stateService;

    public WebUiController(WebUiStateService stateService) {
        this.stateService = stateService;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>rppg-java dashboard</title>
                  <style>
                    :root { color-scheme: light; }
                    body { font-family: "Segoe UI", Tahoma, sans-serif; margin: 24px; background: #f5f7fb; color: #1d2738; }
                    h1 { margin: 0 0 18px; font-size: 22px; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(180px,1fr)); gap: 12px; margin-bottom: 14px; }
                    .card { background: #fff; border: 1px solid #d6deeb; border-radius: 10px; padding: 12px; }
                    .label { font-size: 12px; color: #5f6e84; margin-bottom: 6px; }
                    .value { font-size: 20px; font-weight: 700; }
                    .buttons { display: flex; gap: 8px; margin: 10px 0 14px; flex-wrap: wrap; }
                    button { border: 1px solid #425d8c; background: #4a67a1; color: white; border-radius: 8px; padding: 7px 12px; cursor: pointer; }
                    button:hover { background: #3f5889; }
                    pre { background: #0f172a; color: #e2e8f0; border-radius: 8px; padding: 10px; min-height: 74px; overflow: auto; }
                  </style>
                </head>
                <body>
                  <h1>rppg-java Web UI</h1>
                  <div class="grid">
                    <div class="card"><div class="label">BPM</div><div id="bpm" class="value">--</div></div>
                    <div class="card"><div class="label">Quality</div><div id="quality" class="value">--</div></div>
                    <div class="card"><div class="label">FPS</div><div id="fps" class="value">--</div></div>
                    <div class="card"><div class="label">Window Fill</div><div id="windowFill" class="value">--</div></div>
                    <div class="card"><div class="label">Warnings</div><div id="warnings" class="value">--</div></div>
                  </div>
                  <div class="buttons">
                    <button onclick="control('start')">Start</button>
                    <button onclick="control('stop')">Stop</button>
                    <button onclick="control('reset')">Reset</button>
                  </div>
                  <pre id="status">Connecting to SSE...</pre>
                  <script>
                    const statusEl = document.getElementById('status');
                    const eventSource = new EventSource('/api/sse');
                    eventSource.addEventListener('snapshot', (evt) => {
                      const data = JSON.parse(evt.data);
                      document.getElementById('bpm').textContent = data.bpm.toFixed(1);
                      document.getElementById('quality').textContent = data.quality.toFixed(3);
                      document.getElementById('fps').textContent = data.fps.toFixed(1);
                      document.getElementById('windowFill').textContent = data.windowFill.toFixed(1) + '%';
                      document.getElementById('warnings').textContent = data.warnings || 'none';
                      statusEl.textContent = JSON.stringify(data, null, 2);
                    });
                    eventSource.onerror = () => { statusEl.textContent = 'SSE disconnected'; };
                    async function control(action) {
                      const response = await fetch('/api/control/' + action, { method: 'POST' });
                      statusEl.textContent = 'POST /api/control/' + action + ' -> ' + response.status;
                    }
                  </script>
                </body>
                </html>
                """;
    }

    @GetMapping(value = "/api/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse() {
        return stateService.addEmitter();
    }

    @PostMapping("/api/control/start")
    public ResponseEntity<RppgSnapshot> start() {
        return ResponseEntity.ok(stateService.start());
    }

    @PostMapping("/api/control/stop")
    public ResponseEntity<RppgSnapshot> stop() {
        return ResponseEntity.ok(stateService.stop());
    }

    @PostMapping("/api/control/reset")
    public ResponseEntity<RppgSnapshot> reset() {
        return ResponseEntity.ok(stateService.reset());
    }
}
