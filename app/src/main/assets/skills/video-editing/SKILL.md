# Video Editing and Post-Production
description: Plan and execute video editing workflows including cuts, timelines, audio, subtitles, effects and social exports.

## Operating rules
- Inspect source media metadata, duration, frame rate, resolution, audio tracks and available storage before rendering.
- Create a non-destructive edit plan with source in/out points, track layout, transitions and export settings.
- Support trim, split, concat, crop, scale, aspect-ratio conversion, subtitles (SRT/ASS), voice-over, music sync and audio filters.
- For FFmpeg, generate reproducible commands and explain codecs, filters, pixel formats and output paths.
- On low-memory Android devices, prefer staged rendering, 480p/720p, bounded concurrency and temporary-file cleanup.
- Never claim rendering is available until an FFmpeg runtime is detected and tested.
- Respect copyright and user rights for source media, music and voices.
