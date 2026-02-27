package com.example.rppg.web;

import com.example.rppg.app.RppgSnapshot;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class WebUiController {
    private final WebUiStateService stateService;

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
                    .chart-wrap { background: #fff; border: 1px solid #d6deeb; border-radius: 10px; padding: 12px; margin-bottom: 12px; }
                    #bpmChart { width: 100%; height: 180px; display: block; border: 1px solid #d7dfed; border-radius: 8px; background: #f8fafc; }
                    #avgGChart { width: 100%; height: 180px; display: block; border: 1px solid #d7dfed; border-radius: 8px; background: #f8fafc; }
                    .chart-note { margin-top: 6px; color: #5f6e84; font-size: 12px; }
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
                    <div class="card"><div class="label">BPM Status</div><div id="bpmStatus" class="value">INVALID</div></div>
                    <div class="card"><div class="label">Signal Method</div><div id="activeSignalMethod" class="value">--</div></div>
                    <div class="card"><div class="label">Auto State</div><div id="autoModeState" class="value">--</div></div>
                    <div class="card"><div class="label">Probe Candidate</div><div id="probeCandidate" class="value">--</div></div>
                    <div class="card"><div class="label">Probe Remaining</div><div id="probeSecondsRemaining" class="value">0.0s</div></div>
                    <div class="card"><div class="label">Processing</div><div id="processingStatus" class="value">NORMAL</div></div>
                    <div class="card"><div class="label">Motion Score</div><div id="motionScore" class="value">0.000</div></div>
                    <div class="card"><div class="label">ROI Mode</div><div id="roiMode" class="value">--</div></div>
                    <div class="card"><div class="label">ROI Weights</div><div id="roiWeights" class="small">--</div></div>
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
                  <div class="chart-wrap">
                    <div class="label">BPM History</div>
                    <canvas id="bpmChart" width="900" height="220"></canvas>
                    <div class="chart-note">Last 300 points from SSE. Invalid or LOW_QUALITY points are skipped.</div>
                  </div>
                  <div class="chart-wrap">
                    <div class="label">avgG History</div>
                    <canvas id="avgGChart" width="900" height="220"></canvas>
                    <div class="chart-note">Last 600 points from SSE with auto-scale in the visible window.</div>
                  </div>
                  <pre id="status">Connecting to SSE...</pre>
                  <script>
                    const statusEl = document.getElementById('status');
                    const videoEl = document.getElementById('videoFeed');
                    const videoStatusEl = document.getElementById('videoStatus');
                    const BPM_HISTORY_SIZE = 300;
                    const AVGG_HISTORY_SIZE = 600;
                    const bpmHistory = new Array(BPM_HISTORY_SIZE).fill(null);
                    const avgGHistory = new Array(AVGG_HISTORY_SIZE).fill(null);
                    let bpmWriteIndex = 0;
                    let avgGWriteIndex = 0;
                    let bpmCount = 0;
                    let avgGCount = 0;
                    let bpmChartDirty = true;
                    let avgGChartDirty = true;
                    const bpmCanvas = document.getElementById('bpmChart');
                    const bpmCtx = bpmCanvas.getContext('2d');
                    const avgGCanvas = document.getElementById('avgGChart');
                    const avgGCtx = avgGCanvas.getContext('2d');

                    function clearCharts() {
                      bpmHistory.fill(null);
                      avgGHistory.fill(null);
                      bpmWriteIndex = 0;
                      avgGWriteIndex = 0;
                      bpmCount = 0;
                      avgGCount = 0;
                      bpmChartDirty = true;
                      avgGChartDirty = true;
                    }

                    function pushBpmPoint(data, warnings) {
                      const bpm = data && typeof data.bpm === 'number' ? data.bpm : NaN;
                      const status = data && typeof data.bpmStatus === 'string' ? data.bpmStatus : 'INVALID';
                      const valid = Number.isFinite(bpm) && bpm > 0 && status === 'VALID' && !warnings.includes('LOW_QUALITY');
                      bpmHistory[bpmWriteIndex] = valid ? bpm : null;
                      bpmWriteIndex = (bpmWriteIndex + 1) % BPM_HISTORY_SIZE;
                      bpmCount = Math.min(bpmCount + 1, BPM_HISTORY_SIZE);
                      bpmChartDirty = true;
                    }

                    function pushAvgGPoint(data) {
                      const avgG = data && typeof data.avgG === 'number' ? data.avgG : NaN;
                      avgGHistory[avgGWriteIndex] = Number.isFinite(avgG) ? avgG : null;
                      avgGWriteIndex = (avgGWriteIndex + 1) % AVGG_HISTORY_SIZE;
                      avgGCount = Math.min(avgGCount + 1, AVGG_HISTORY_SIZE);
                      avgGChartDirty = true;
                    }

                    function bpmAt(indexFromOldest) {
                      const start = (bpmWriteIndex - bpmCount + BPM_HISTORY_SIZE) % BPM_HISTORY_SIZE;
                      const idx = (start + indexFromOldest) % BPM_HISTORY_SIZE;
                      return bpmHistory[idx];
                    }

                    function avgGAt(indexFromOldest) {
                      const start = (avgGWriteIndex - avgGCount + AVGG_HISTORY_SIZE) % AVGG_HISTORY_SIZE;
                      const idx = (start + indexFromOldest) % AVGG_HISTORY_SIZE;
                      return avgGHistory[idx];
                    }

                    function resizeCanvas(canvas, ctx) {
                      const dpr = window.devicePixelRatio || 1;
                      const rect = canvas.getBoundingClientRect();
                      const cssWidth = Math.max(200, Math.floor(rect.width));
                      const cssHeight = 180;
                      canvas.width = Math.floor(cssWidth * dpr);
                      canvas.height = Math.floor(cssHeight * dpr);
                      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
                    }

                    function resizeCharts() {
                      resizeCanvas(bpmCanvas, bpmCtx);
                      resizeCanvas(avgGCanvas, avgGCtx);
                      bpmChartDirty = true;
                      avgGChartDirty = true;
                    }

                    function drawBpmChart() {
                      if (!bpmChartDirty) {
                        return;
                      }
                      bpmChartDirty = false;

                      const w = Math.max(1, bpmCanvas.clientWidth);
                      const h = 180;
                      const left = 36;
                      const right = 10;
                      const top = 10;
                      const bottom = 22;
                      const plotW = Math.max(1, w - left - right);
                      const plotH = Math.max(1, h - top - bottom);
                      const minBpm = 40;
                      const maxBpm = 180;

                      bpmCtx.clearRect(0, 0, w, h);
                      bpmCtx.fillStyle = '#f8fafc';
                      bpmCtx.fillRect(0, 0, w, h);

                      bpmCtx.strokeStyle = '#d0d9ea';
                      bpmCtx.lineWidth = 1;
                      for (let i = 0; i <= 4; i++) {
                        const y = top + (i / 4) * plotH;
                        bpmCtx.beginPath();
                        bpmCtx.moveTo(left, y);
                        bpmCtx.lineTo(left + plotW, y);
                        bpmCtx.stroke();

                        const label = Math.round(maxBpm - (i / 4) * (maxBpm - minBpm));
                        bpmCtx.fillStyle = '#64748b';
                        bpmCtx.font = '11px Segoe UI';
                        bpmCtx.fillText(String(label), 4, y + 3);
                      }

                      bpmCtx.strokeStyle = '#1d4ed8';
                      bpmCtx.lineWidth = 2;
                      bpmCtx.beginPath();
                      let started = false;
                      let validCount = 0;
                      let lastX = 0;
                      let lastY = 0;
                      for (let i = 0; i < bpmCount; i++) {
                        const v = bpmAt(i);
                        if (v == null) {
                          started = false;
                          continue;
                        }
                        validCount++;
                        const x = left + (bpmCount <= 1 ? 0 : (i / (bpmCount - 1)) * plotW);
                        const clamped = Math.max(minBpm, Math.min(maxBpm, v));
                        const y = top + (1 - ((clamped - minBpm) / (maxBpm - minBpm))) * plotH;
                        if (!started) {
                          bpmCtx.moveTo(x, y);
                          started = true;
                        } else {
                          bpmCtx.lineTo(x, y);
                        }
                        lastX = x;
                        lastY = y;
                      }
                      bpmCtx.stroke();

                      if (validCount > 0) {
                        bpmCtx.fillStyle = '#1d4ed8';
                        bpmCtx.beginPath();
                        bpmCtx.arc(lastX, lastY, 3, 0, Math.PI * 2);
                        bpmCtx.fill();
                      } else {
                        bpmCtx.fillStyle = '#64748b';
                        bpmCtx.font = '12px Segoe UI';
                        bpmCtx.fillText('Waiting for valid BPM points...', left + 8, top + 18);
                      }
                    }

                    function drawAvgGChart() {
                      if (!avgGChartDirty) {
                        return;
                      }
                      avgGChartDirty = false;

                      const w = Math.max(1, avgGCanvas.clientWidth);
                      const h = 180;
                      const left = 52;
                      const right = 10;
                      const top = 10;
                      const bottom = 22;
                      const plotW = Math.max(1, w - left - right);
                      const plotH = Math.max(1, h - top - bottom);

                      avgGCtx.clearRect(0, 0, w, h);
                      avgGCtx.fillStyle = '#f8fafc';
                      avgGCtx.fillRect(0, 0, w, h);

                      let min = Number.POSITIVE_INFINITY;
                      let max = Number.NEGATIVE_INFINITY;
                      let validCount = 0;
                      for (let i = 0; i < avgGCount; i++) {
                        const v = avgGAt(i);
                        if (v == null) {
                          continue;
                        }
                        validCount++;
                        if (v < min) min = v;
                        if (v > max) max = v;
                      }

                      if (validCount === 0) {
                        avgGCtx.fillStyle = '#64748b';
                        avgGCtx.font = '12px Segoe UI';
                        avgGCtx.fillText('Waiting for avgG points...', left + 8, top + 18);
                        return;
                      }

                      if (max <= min) {
                        const pad = Math.max(1.0, Math.abs(min) * 0.05);
                        min -= pad;
                        max += pad;
                      } else {
                        const pad = (max - min) * 0.1;
                        min -= pad;
                        max += pad;
                      }

                      avgGCtx.strokeStyle = '#d0d9ea';
                      avgGCtx.lineWidth = 1;
                      for (let i = 0; i <= 4; i++) {
                        const y = top + (i / 4) * plotH;
                        avgGCtx.beginPath();
                        avgGCtx.moveTo(left, y);
                        avgGCtx.lineTo(left + plotW, y);
                        avgGCtx.stroke();

                        const labelValue = max - (i / 4) * (max - min);
                        avgGCtx.fillStyle = '#64748b';
                        avgGCtx.font = '11px Segoe UI';
                        avgGCtx.fillText(labelValue.toFixed(1), 4, y + 3);
                      }

                      avgGCtx.strokeStyle = '#15803d';
                      avgGCtx.lineWidth = 2;
                      avgGCtx.beginPath();
                      let started = false;
                      let lastX = 0;
                      let lastY = 0;
                      for (let i = 0; i < avgGCount; i++) {
                        const v = avgGAt(i);
                        if (v == null) {
                          started = false;
                          continue;
                        }
                        const x = left + (avgGCount <= 1 ? 0 : (i / (avgGCount - 1)) * plotW);
                        const y = top + (1 - ((v - min) / (max - min))) * plotH;
                        if (!started) {
                          avgGCtx.moveTo(x, y);
                          started = true;
                        } else {
                          avgGCtx.lineTo(x, y);
                        }
                        lastX = x;
                        lastY = y;
                      }
                      avgGCtx.stroke();

                      avgGCtx.fillStyle = '#15803d';
                      avgGCtx.beginPath();
                      avgGCtx.arc(lastX, lastY, 3, 0, Math.PI * 2);
                      avgGCtx.fill();
                    }

                    function chartLoop() {
                      drawBpmChart();
                      drawAvgGChart();
                      window.requestAnimationFrame(chartLoop);
                    }

                    window.addEventListener('resize', resizeCharts);
                    resizeCharts();
                    chartLoop();

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
                      const bpm = data && typeof data.bpm === 'number' ? data.bpm : NaN;
                      const bpmStatus = data && typeof data.bpmStatus === 'string' ? data.bpmStatus : 'INVALID';
                      const activeSignalMethod = data && typeof data.activeSignalMethod === 'string' ? data.activeSignalMethod : '--';
                      const autoModeState = data && typeof data.autoModeState === 'string' ? data.autoModeState : '--';
                      const probeCandidate = data && typeof data.probeCandidate === 'string' ? data.probeCandidate : '--';
                      const probeSecondsRemaining = data && typeof data.probeSecondsRemaining === 'number' ? data.probeSecondsRemaining : 0.0;
                      const processingStatus = data && typeof data.processingStatus === 'string' ? data.processingStatus : 'NORMAL';
                      const motionScore = data && typeof data.motionScore === 'number' ? data.motionScore : 0.0;
                      const roiMode = data && typeof data.roiMode === 'string' ? data.roiMode : '--';
                      const roiWeights = data && Array.isArray(data.roiWeights)
                        ? data.roiWeights.map((v) => Number(v).toFixed(2)).join(' / ')
                        : '--';
                      document.getElementById('bpm').textContent = Number.isFinite(bpm) ? bpm.toFixed(1) : '--';
                      document.getElementById('bpmStatus').textContent = bpmStatus;
                      document.getElementById('activeSignalMethod').textContent = activeSignalMethod;
                      document.getElementById('autoModeState').textContent = autoModeState;
                      document.getElementById('probeCandidate').textContent = probeCandidate;
                      document.getElementById('probeSecondsRemaining').textContent = probeSecondsRemaining.toFixed(1) + 's';
                      document.getElementById('processingStatus').textContent = processingStatus;
                      document.getElementById('motionScore').textContent = motionScore.toFixed(3);
                      document.getElementById('roiMode').textContent = roiMode;
                      document.getElementById('roiWeights').textContent = roiWeights;
                      document.getElementById('quality').textContent = data.quality.toFixed(3);
                      document.getElementById('fps').textContent = data.fps.toFixed(1);
                      document.getElementById('windowFill').textContent = data.windowFill.toFixed(1) + '%';
                      document.getElementById('sessionFilePath').textContent = data.sessionFilePath || '--';
                      document.getElementById('sessionDurationSec').textContent = (data.sessionDurationSec || 0).toFixed(1) + 's';
                      document.getElementById('sessionRowCount').textContent = String(data.sessionRowCount || 0);
                      const warnings = Array.isArray(data.warnings) ? data.warnings : [];
                      pushBpmPoint(data, warnings);
                      pushAvgGPoint(data);
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
                        clearCharts();
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
