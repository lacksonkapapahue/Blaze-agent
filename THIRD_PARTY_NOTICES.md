# Third-party notices

Copied from Blaze Desktop. On the phone the router is built from blazerouter/ and shipped as libblazerouter.so.

## OmniRoute

BlazeRouter (`internal/router`, its dashboard in `internal/router/web` and the model catalog `internal/router/omniroute_catalog.json`) is a Go port of the routing core of OmniRoute: the auto/* presets and their category:tier ranking, combos and strategies, last-known-good preference, chaos fan-out and the provider catalog.

Source: https://github.com/diegosouzapw/OmniRoute

```
MIT License

Copyright (c) 2026 diegosouzapw

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## three.js

The Design view bundles three.js r170 (`app/src/main/assets/design/three`: `three.module.min.js`, `OrbitControls.js`, `GLTFExporter.js`) so 3D designs open offline, and since v0.23.3 also three.js r162 (`app/src/main/assets/design/three-r162`: `three.module.min.js`, `OrbitControls.js`, `GLTFExporter.js`, `TextureUtils.js`, from the npm package three@0.162.0), the last release that still runs on WebGL 1, for phones whose WebView has WebGL 2 turned off. Both are MIT-licensed by the three.js authors (each folder carries its LICENSE; r162's copyright line reads 2010-2024 three.js authors too).

Source: https://github.com/mrdoob/three.js

```
The MIT License

Copyright © 2010-2024 three.js authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Linux desktop (v0.25.1), downloaded on first Start, not bundled
The Desktop view installs these free and open-source packages from Alpine Linux's official repositories into Blaze's
Alpine Linux on the phone, only when the user taps Start (or approves the agent's `desktop` start). None of them is
shipped inside the APK. Each keeps its own licence (as packaged by Alpine; `apk info -L <pkg>` lists its files):

| Package | Project | Licence |
|---|---|---|
| xfce4, xfce4-session, xfwm4, xfdesktop, xfce4-panel, xfce4-terminal, thunar, mousepad | Xfce | GPL-2.0-or-later / LGPL-2.0-or-later |
| tigervnc (Xvnc, vncpasswd) | TigerVNC | GPL-2.0-or-later |
| xdotool | xdotool | BSD-3-Clause |
| imagemagick (import) | ImageMagick | ImageMagick License (Apache-2.0 style) |
| netsurf | NetSurf | GPL-2.0-only |
| dbus, dbus-x11 | freedesktop.org D-Bus | AFL-2.1 OR GPL-2.0-or-later |
| font-dejavu | DejaVu Fonts | Bitstream Vera Fonts License (free) |
| adwaita-icon-theme | GNOME | LGPL-3.0-or-later OR CC-BY-SA-3.0 |

Blaze's VNC viewer (`desktop/Rfb.kt`) is Blaze's own code, written from the public RFB protocol specification (RFC 6143).

## Mermaid (v0.26.3, bundled for offline diagrams)

Chat replies draw Mermaid diagrams on the phone, with no network, from the bundled mermaid 11.17.2
(`app/src/main/assets/diagram/mermaid.min.js`, the npm package's IIFE build, with its LICENSE beside it).
Mermaid is MIT-licensed by Knut Sveidqvist and contributors.

Source: https://github.com/mermaid-js/mermaid

```
The MIT License (MIT)

Copyright (c) 2014 - 2022 Knut Sveidqvist

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

mermaid.min.js also contains these libraries, each under its own licence:

| Library | Licence |
|---|---|
| dagre-d3-es, cytoscape (+ cose-bilkent, fcose), katex, khroma, marked, roughjs, dayjs, stylis, uuid, langium, lodash-es, ts-dedent, @braintree/sanitize-url, @iconify/utils | MIT |
| d3 and its d3-* modules | ISC |
| d3-sankey | BSD-3-Clause |
| chevrotain | Apache-2.0 |
| DOMPurify | MPL-2.0 OR Apache-2.0 |
