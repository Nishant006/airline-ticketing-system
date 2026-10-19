# Architecture diagram

[`architecture.svg`](architecture.svg) is the standalone architecture diagram.
The equivalent editable Mermaid flow is embedded in
[`../architecture.md`](../architecture.md). The HLD PDF is a text export of that
document; use the SVG or GitHub Mermaid rendering for the diagram.

On macOS, regenerate the PDF after editing the HLD:

```bash
/usr/sbin/cupsfilter -i text/plain -m application/pdf hld/architecture.md > hld/architecture.pdf
```
