package com.example.rppg.web;

import com.example.rppg.app.RppgSnapshot;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@Slf4j
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
                    .video-wrap { background: #fff; border: 1px solid #d6deeb; border-radius: 10px; padding: 10px; margin-bottom: 12px; }
                    #videoFeed { width: 100%; max-width: 860px; border-radius: 8px; border: 1px solid #c9d5e8; background: #111827; }
                    #videoStatus { margin-top: 6px; color: #5f6e84; font-size: 13px; }
                    .warnings-panel { min-height: 72px; }
                    .warnings-panel.active { border-color: #dc2626; box-shadow: inset 0 0 0 1px #dc2626; background: #fff1f2; }
                    #warningsList { display: flex; gap: 6px; flex-wrap: wrap; }
                    .warning-chip { font-size: 12px; line-height: 1; font-weight: 700; color: #991b1b; background: #fecaca; border: 1px solid #f87171; border-radius: 999px; padding: 5px 8px; }
                    .warning-none { color: #5f6e84; font-size: 13px; font-weight: 600; }
                    .small { font-size: 13px; font-weight: 600; word-break: break-all; }
                    pre { background: #0f172a; color: #e2e8f0; border-radius: 8px; padding: 10px; min-height: 74px; overflow: auto; }
                  </style>
                </head>
                <body>
                  <h1>rppg-java Web UI</h1>
                  <div class="video-wrap">
                    <img id="videoFeed" src="/api/video.mjpg" alt="rPPG camera preview">
                    <div id="videoStatus">Video stream: waiting for engine start...</div>
                  </div>
                  <div class="grid">
                    <div class="card"><div class="label">BPM</div><div id="bpm" class="value">--</div></div>
                    <div class="card"><div class="label">Quality</div><div id="quality" class="value">--</div></div>
                    <div class="card"><div class="label">FPS</div><div id="fps" class="value">--</div></div>
                    <div class="card"><div class="label">Window Fill</div><div id="windowFill" class="value">--</div></div>
                    <div class="card"><div class="label">Session File</div><div id="sessionFilePath" class="small">--</div></div>
                    <div class="card"><div class="label">Session Duration</div><div id="sessionDurationSec" class="value">--</div></div>
                    <div class="card"><div class="label">Session Rows</div><div id="sessionRowCount" class="value">--</div></div>
                    <div id="warningsCard" class="card warnings-panel"><div class="label">Warnings</div><div id="warningsList"><span class="warning-none">none</span></div></div>
                  </div>
                  <div class="buttons">
                    <button onclick="control('start')">Start</button>
                    <button onclick="control('stop')">Stop</button>
                    <button onclick="control('reset')">Reset</button>
                  </div>
                  <pre id="status">Connecting to SSE...</pre>
                  <script>
                    const statusEl = document.getElementById('status');
                    const videoEl = document.getElementById('videoFeed');
                    const videoStatusEl = document.getElementById('videoStatus');

                    function reconnectVideo() {
                      videoEl.src = '/api/video.mjpg?ts=' + Date.now();
                    }

                    videoEl.onerror = () => {
                      videoStatusEl.textContent = 'Video unavailable: press Start to launch engine.';
                    };
                    videoEl.onload = () => {
                      videoStatusEl.textContent = 'Video stream connected.';
                    };

                    const eventSource = new EventSource('/api/sse');
                    eventSource.addEventListener('snapshot', (evt) => {
                      const data = JSON.parse(evt.data);
                      document.getElementById('bpm').textContent = data.bpm.toFixed(1);
                      document.getElementById('quality').textContent = data.quality.toFixed(3);
                      document.getElementById('fps').textContent = data.fps.toFixed(1);
                      document.getElementById('windowFill').textContent = data.windowFill.toFixed(1) + '%';
                      document.getElementById('sessionFilePath').textContent = data.sessionFilePath || '--';
                      document.getElementById('sessionDurationSec').textContent = (data.sessionDurationSec || 0).toFixed(1) + 's';
                      document.getElementById('sessionRowCount').textContent = String(data.sessionRowCount || 0);
                      const warnings = Array.isArray(data.warnings) ? data.warnings : [];
                      const warningsCard = document.getElementById('warningsCard');
                      const warningsList = document.getElementById('warningsList');
                      warningsList.innerHTML = '';
                      if (warnings.length === 0) {
                        warningsCard.classList.remove('active');
                        const none = document.createElement('span');
                        none.className = 'warning-none';
                        none.textContent = 'none';
                        warningsList.appendChild(none);
                      } else {
                        warningsCard.classList.add('active');
                        warnings.forEach((code) => {
                          const chip = document.createElement('span');
                          chip.className = 'warning-chip';
                          chip.textContent = code;
                          warningsList.appendChild(chip);
                        });
                      }
                      statusEl.textContent = JSON.stringify(data, null, 2);
                    });
                    eventSource.onerror = () => { statusEl.textContent = 'SSE disconnected'; };
                    async function control(action) {
                      const response = await fetch('/api/control/' + action, { method: 'POST' });
                      statusEl.textContent = 'POST /api/control/' + action + ' -> ' + response.status;
                      if (action === 'start' || action === 'reset') {
                        reconnectVideo();
                      }
                      if (action === 'stop') {
                        videoStatusEl.textContent = 'Video stream stopped.';
                      }
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

    @GetMapping(value = "/api/video.mjpg")
    public void mjpeg(HttpServletResponse response) throws IOException {
        if (!stateService.isRunning() && stateService.getLatestJpegFrame() == null) {
            response.sendError(HttpServletResponse.SC_CONFLICT, "Engine is not running. Press Start.");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Pragma", "no-cache");

        byte[] boundary = "--frame\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] typeHeader = "Content-Type: image/jpeg\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] endHeaders = "\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] partBreak = "\r\n".getBytes(StandardCharsets.US_ASCII);

        try (ServletOutputStream out = response.getOutputStream()) {
            while (true) {
                byte[] jpeg = stateService.getLatestJpegFrame();
                if (jpeg == null) {
                    if (!stateService.isRunning()) {
                        break;
                    }
                    sleepQuietly(80L);
                    continue;
                }

                byte[] lengthHeader = ("Content-Length: " + jpeg.length + "\r\n").getBytes(StandardCharsets.US_ASCII);
                out.write(boundary);
                out.write(typeHeader);
                out.write(lengthHeader);
                out.write(endHeaders);
                out.write(jpeg);
                out.write(partBreak);
                out.flush();

                sleepQuietly(80L);
            }
        } catch (IOException e) {
            log.warn("MJPEG stream error: {}", e.getMessage());
        }
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

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
