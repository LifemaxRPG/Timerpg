# The playtest recorder (spec, 2026-11-23)

The owner plays on the S25; everything useful for tuning goes to the private `reports` branch
(`play/<date>/<session>.jsonl.gz`, plus `talk/` for conversations: CONVERSATION.md "The conversation log").

**Already recorded (keep):**
- crashes and Android's exit reasons;
- frame spikes with their causes, and a 10 s breadcrumb window (FrameSpikes, FrameWork);
- the NPU fit test;
- the overnight writer's runs;
- screenshots on request.

## To add, most valuable first
1. **A continuous session timeline, every second.**
   - Frame time: p50, p95, p99 and the worst frame, with GPU time and CPU time.
   - Where and what: town or world position, the year and hour, the camera height and zoom, the activity (walking,
     riding, talking, inside a building, menu).
   - What's on screen: draws, triangles, people and buildings near.
   - Spikes alone can't show slow-but-steady areas.
2. **Heat and power:**
   - the thermal status and ADPF thermal headroom (forecast);
   - the battery level, current (`BATTERY_PROPERTY_CURRENT_NOW`) and temperature;
   - charging or not;
   - CPU and GPU frequencies where readable.
   This gives energy per activity and the time to throttling.
3. **Performance traces on demand:** the "mark this" button (and any severe spike) also asks Android's
   `ProfilingManager` (Android 15+) for a system trace of the last seconds. That's a Perfetto trace with every thread,
   the CPU cores, frequencies and GPU work, for real root-cause work.
4. **Loading and streaming:**
   - cold start by phase;
   - the town build;
   - terrain chunk and building request-to-ready times;
   - the disk cache hit rate;
   - texture uploads;
   - every hitch tied to what streamed in it.
5. **Memory over time:**
   - PSS, Java/native/graphics split;
   - our own GPU and town accounting lines;
   - trim-memory callbacks;
   - how close the low-memory killer came.
6. **AI:**
   - the queue depth;
   - the pre-written bank hit rate vs template vs live;
   - question to first word and to the last word;
   - tok/s by unit (NPU/GPU/CPU);
   - the speech recognizer's latency and confidence;
   - voice (TTS) latency;
   - AI work overlapping heavy frames.
7. **Input to response:** touch-down to the first changed frame (feel), and camera-control smoothness.
8. **Settings in effect:** the quality tier and every auto-tuned value, the app version and build, so each number is
   tied to the configuration that made it.
9. **Play patterns** (for both tuning and design):
   - a heatmap of where the player goes;
   - time per activity;
   - who they talk to and what they use;
   - where they quit or get stuck.

## Rules
- Cheap: about 1 KB/s compressed, no allocation per frame, written off the render thread, uploaded when charging or
  on Wi-Fi.
- The data stays in the private repo.
- Claude summarizes each session in plain words for the owner and turns it into tuning changes and fixes.
