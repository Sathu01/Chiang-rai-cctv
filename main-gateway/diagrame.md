
```mermaid
<flowchart TD

A[RTSP URL + Stream Name Received] --> B[Create Stream Folder]
B --> C[Setup FFmpeg Connection]
C --> D{RTSP Connected?}

D -- Yes --> E[Start Worker Thread]
E --> F[FFmpeg Writes .ts Segments]
F --> G[Update .m3u8 Playlist]
G --> H[Frontend Plays Stream]

D -- No --> R[Retry Loop Until Success]
R --> C

%% Auto Recovery
E --> X{Connection Lost?}
X -- Yes --> Y[Terminate Old Thread]
Y --> R
X -- No --> F

%% Delete Flow
I[Delete Stream Request] --> J[Stop FFmpeg + Thread]
J --> K[Delete Stream Folder]

%% Status Check Flow
S[Every 5 Minutes] --> T[Ping Camera]
T --> U{Camera Responds?}
U -- Yes --> V[Set Status: Online]
U -- No --> W[Set Status: Offline]>
```