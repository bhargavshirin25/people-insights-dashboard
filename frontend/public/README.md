Static assets served from the site root.

`robin.png` — the mark shown in the top bar, left of "Robin Insights". Drop the file in here as
exactly `robin.png` and it appears on the next request; no rebuild or restart is needed in dev. It is
rendered at 30x30 CSS pixels with `object-contain`, so a square source of 60x60 or larger stays crisp
on a retina display. Until the file exists the header renders with an empty space where it will go.
