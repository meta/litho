/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @format
 */

// Draws the custom Layout docs' figures, in a light and a dark version, from the numbers in
// CustomLayoutGuideConformanceTest's `start_figure_numbers` block, which its F tests assert. Copies
// the samples' screenshots, which CustomLayoutScreenshotTest checks against the samples, next to
// them.
//
//   node website/scripts/custom-layout-figures.js          writes the SVGs and copies the screenshots
//   node website/scripts/custom-layout-figures.js --check  fails if a committed figure or screenshot
//                                                          is out of date, or if an image the docs
//                                                          use is missing

'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const TEST = path.join(
  ROOT,
  'litho-it/src/test/com/facebook/litho/customlayout/CustomLayoutGuideConformanceTest.kt',
);
const OUT = path.join(ROOT, 'website/static/images');
const SCREENSHOTS = path.join(ROOT, 'sample/src/test/resources/customlayout/screenshots');
const DOCS = ['docs/layout/custom-layout.mdx', 'docs/layout/custom-layout-rules.mdx'];

const THEMES = {
  light: {
    ink: '#1c1e21',
    muted: '#5f6368',
    room: '#1c1e21',
    exactFill: '#eef1f5',
    boxFill: '#d6e4ff',
    boxStroke: '#3b6fd6',
    edge: '#c2255c',
    down: '#0b7285',
    up: '#b45309',
    ghost: '#6b7280',
  },
  dark: {
    ink: '#e3e3e3',
    muted: '#a8abb0',
    room: '#e3e3e3',
    exactFill: '#2a2d33',
    boxFill: '#1e3a6e',
    boxStroke: '#8fb3ff',
    edge: '#ff8fb8',
    down: '#66d9e8',
    up: '#fbbf24',
    ghost: '#9ca3af',
  },
};

const FONT =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
const MONO = "SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace";

function readFigures() {
  const source = fs.readFileSync(TEST, 'utf8');
  const block = /\/\/ start_figure_numbers([\s\S]*?)\/\/ end_figure_numbers/.exec(source);
  if (block == null) {
    throw new Error(`No start_figure_numbers block in ${TEST}`);
  }
  const figures = {};
  for (const match of block[1].matchAll(/"([\w.]+)" to (-?\d+)/g)) {
    figures[match[1]] = Number(match[2]);
  }
  return key => {
    if (!(key in figures)) {
      throw new Error(`Figure number ${key} is missing from the test's block`);
    }
    return figures[key];
  };
}

/** Collects the elements of one figure and renders them for a theme. */
class Canvas {
  constructor(width, height, title) {
    this.width = width;
    this.height = height;
    this.title = title;
    this.parts = [];
  }

  add(render) {
    this.parts.push(render);
  }

  /** A room: dashed, tinted when exact, open at the end when unbounded. */
  room(x, y, w, h, {exact = false, label}) {
    this.add(
      t =>
        `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${
          exact ? t.exactFill : 'none'
        }" stroke="${t.room}" stroke-width="1.5" stroke-dasharray="6 4"/>`,
    );
    if (label != null) {
      this.text(x, y - 6, label, {muted: true, size: 12});
    }
  }

  /** A child's border box: filled. */
  box(x, y, w, h, label) {
    this.add(
      t =>
        `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${t.boxFill}" stroke="${t.boxStroke}" stroke-width="1.5"/>`,
    );
    if (label != null) {
      this.text(x + w / 2, y + h / 2 + 4, label, {anchor: 'middle', size: 12});
    }
  }

  /** A declared size the parent overrode: a ghost outline. */
  ghost(x, y, w, h, label) {
    this.add(
      t =>
        `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="none" stroke="${t.ghost}" stroke-width="1.5" stroke-dasharray="2 3"/>`,
    );
    if (label != null) {
      this.text(x + 4, y - 6, label, {color: 'ghost', size: 12});
    }
  }

  /** Margins or padding: hatched. */
  hatch(x, y, w, h) {
    this.add(
      t =>
        `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="url(#hatch-${t.name})" stroke="none"/>`,
    );
  }

  /** A container's outline: solid. */
  frame(x, y, w, h) {
    this.add(
      t =>
        `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="none" stroke="${t.ink}" stroke-width="1.5"/>`,
    );
  }

  /** The start edge: a bar, with a flag pointing towards the end. */
  startEdge(x, y, h, rtl) {
    const flag = rtl ? -7 : 7;
    this.add(
      t =>
        `<line x1="${x}" y1="${y - 4}" x2="${x}" y2="${y + h + 4}" stroke="${t.edge}" stroke-width="3"/>` +
        `<path d="M ${x} ${y - 4} l ${flag} 4 l ${-flag} 4 z" fill="${t.edge}"/>`,
    );
  }

  /** A dimension line from x1 to x2, with its label after it. */
  dimension(x1, x2, y, label, {labelAt = 'after'} = {}) {
    const left = Math.min(x1, x2);
    const right = Math.max(x1, x2);
    this.add(
      t =>
        `<line x1="${left}" y1="${y}" x2="${right}" y2="${y}" stroke="${t.muted}" stroke-width="1" marker-start="url(#tick-${t.name})" marker-end="url(#tick-${t.name})"/>`,
    );
    if (labelAt === 'after') {
      this.text(right + 8, y + 4, label, {size: 12});
    } else {
      this.text(left - 8, y + 4, label, {size: 12, anchor: 'end'});
    }
  }

  /** A vertical arrow: down for constraints, up for sizes. */
  arrow(x, y1, y2, kind, label) {
    const colour = kind === 'down' ? 'down' : 'up';
    this.add(
      t =>
        `<line x1="${x}" y1="${y1}" x2="${x}" y2="${y2}" stroke="${t[colour]}" stroke-width="2" marker-end="url(#${colour}-${t.name})"/>`,
    );
    if (label != null) {
      this.text(x + 8, (y1 + y2) / 2 + 4, label, {color: colour, size: 12});
    }
  }

  dot(x, y) {
    this.add(t => `<circle cx="${x}" cy="${y}" r="3.5" fill="${t.edge}"/>`);
  }

  text(x, y, value, {anchor = 'start', size = 13, mono = false, muted = false, color, weight} = {}) {
    const escaped = String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
    this.add(t => {
      const fill = color != null ? t[color] : muted ? t.muted : t.ink;
      const fontWeight = weight != null ? ` font-weight="${weight}"` : '';
      return `<text x="${x}" y="${y}" text-anchor="${anchor}" font-size="${size}" font-family="${
        mono ? MONO : FONT
      }" fill="${fill}"${fontWeight}>${escaped}</text>`;
    });
  }

  render(themeName) {
    const t = {name: themeName, ...THEMES[themeName]};
    const defs =
      `<defs>` +
      `<pattern id="hatch-${t.name}" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><line x1="0" y1="0" x2="0" y2="6" stroke="${t.muted}" stroke-width="1.5"/></pattern>` +
      `<marker id="tick-${t.name}" viewBox="0 0 2 10" refX="1" refY="5" markerWidth="2" markerHeight="10" orient="auto"><line x1="1" y1="0" x2="1" y2="10" stroke="${t.muted}" stroke-width="2"/></marker>` +
      `<marker id="down-${t.name}" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M 0 0 L 10 5 L 0 10 z" fill="${t.down}"/></marker>` +
      `<marker id="up-${t.name}" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M 0 0 L 10 5 L 0 10" fill="none" stroke="${t.up}" stroke-width="2"/></marker>` +
      `</defs>`;
    const body = this.parts.map(part => part(t)).join('\n  ');
    return (
      `<svg xmlns="http://www.w3.org/2000/svg" width="${this.width}" height="${this.height}" viewBox="0 0 ${this.width} ${this.height}" role="img">\n` +
      `  <title>${this.title}</title>\n  ${defs}\n  ${body}\n</svg>\n`
    );
  }
}

/** F1: constraints come down, measured sizes come up, and positions are set when placing. */
function anatomy(fig) {
  const s = 1.5;
  const c = new Canvas(720, 250, 'A Layout measures, declares its size, and places');
  const x0 = 40;
  const y0 = 70;
  const width = fig('F1.layoutWidth') * s;
  const padding = fig('F1.padding') * s;
  const height = 120;
  c.arrow(x0 + 40, 14, y0 - 6, 'down', `constraints from the parent`);
  c.frame(x0, y0, width, height);
  c.hatch(x0, y0, width, padding);
  c.hatch(x0, y0 + height - padding, width, padding);
  c.hatch(x0, y0 + padding, padding, height - 2 * padding);
  c.hatch(x0 + width - padding, y0 + padding, padding, height - 2 * padding);
  c.room(x0 + padding, y0 + padding, width - 2 * padding, height - 2 * padding, {
    exact: true,
  });
  c.text(x0 + padding + 6, y0 + height - padding - 8, `exactly ${fig('F1.contentWidth')} wide: the content box`, {
    muted: true,
    size: 12,
  });
  const childX = x0 + fig('F1.childX') * s;
  const childY = y0 + fig('F1.childY') * s;
  c.box(childX, childY, fig('F1.childWidth') * s, fig('F1.childHeight') * s);
  c.dot(childX, childY);
  c.dimension(x0, x0 + width, y0 + height + 18, `width(${fig('F1.layoutWidth')}), padding ${fig('F1.padding')}`);
  const notes = x0 + width + 28;
  c.text(notes, y0 + 6, '1. measure(room)', {mono: true, size: 12, color: 'down'});
  c.text(notes, y0 + 24, '   the room goes down', {muted: true, size: 12});
  c.text(notes, y0 + 48, `2. Placeable ${fig('F1.childWidth')} × ${fig('F1.childHeight')}`, {
    mono: true,
    size: 12,
    color: 'up',
  });
  c.text(notes, y0 + 66, '   the size comes up', {muted: true, size: 12});
  c.text(notes, y0 + 90, '3. layout(w, h) { place }', {mono: true, size: 12});
  c.text(notes, y0 + 108, `   (0, 0) is (${fig('F1.childX')}, ${fig('F1.childY')})`, {muted: true, size: 12});
  return c;
}

/** F2: the room, the margins, the box, width and measuredWidth. */
function placeable(fig) {
  const s = 1.6;
  const c = new Canvas(720, 250, 'A Placeable: width and measuredWidth');
  const x0 = 40;
  let y = 40;
  c.text(x0, y - 18, `width(${fig('F2.marginOwnWidth')}).margin(all = ${fig('F2.margin')}), at most ${fig('F2.marginRoom')} wide`, {
    mono: true,
    size: 12,
  });
  c.room(x0, y, fig('F2.marginRoom') * s, 40, {});
  const margin = fig('F2.margin') * s;
  c.hatch(x0, y + 4, margin, 32);
  c.box(x0 + fig('F2.marginBoxX') * s, y + 4, fig('F2.marginOwnWidth') * s, 32, 'box');
  c.hatch(x0 + margin + fig('F2.marginOwnWidth') * s, y + 4, margin, 32);
  c.startEdge(x0, y, 40, false);
  c.dimension(x0, x0 + fig('F2.marginMeasuredWidth') * s, y + 56, `measuredWidth = ${fig('F2.marginMeasuredWidth')}, margins included`);
  y = 160;
  c.text(x0, y - 18, `width(${fig('F2.overflowOwnWidth')}), at most ${fig('F2.overflowRoom')} wide`, {
    mono: true,
    size: 12,
  });
  c.room(x0, y, fig('F2.overflowRoom') * s, 40, {});
  c.box(x0, y + 4, fig('F2.overflowOwnWidth') * s, 32, 'box');
  c.startEdge(x0, y, 40, false);
  c.dimension(x0, x0 + fig('F2.overflowWidth') * s, y + 56, `width = ${fig('F2.overflowWidth')}, kept within the room`);
  c.dimension(x0, x0 + fig('F2.overflowMeasuredWidth') * s, y + 76, `measuredWidth = ${fig('F2.overflowMeasuredWidth')}`);
  return c;
}

/** F3: a child that doesn't fit is drawn from its start edge. */
function startEdge(fig) {
  const s = 1.6;
  const c = new Canvas(720, 290, 'A child that does not fit starts at its start edge');
  const x0 = 40;
  const rows = [
    {
      label: `width(${fig('F3.shortWidth')}), exactly ${fig('F3.shortRoom')} wide`,
      room: fig('F3.shortRoom'),
      exact: true,
      width: fig('F3.shortWidth'),
      x: fig('F3.shortX'),
      note: `x = ${fig('F3.shortX')}, ${fig('F3.shortRoom') - fig('F3.shortWidth')} short of the end`,
      rtl: false,
    },
    {
      label: `width(${fig('F3.overWidth')}), at most ${fig('F3.overRoom')} wide`,
      room: fig('F3.overRoom'),
      exact: false,
      width: fig('F3.overWidth'),
      x: fig('F3.overX'),
      note: `x = ${fig('F3.overX')}, ${fig('F3.overWidth') - fig('F3.overRoom')} past the end`,
      rtl: false,
    },
  ];
  let y = 40;
  for (const row of rows) {
    c.text(x0, y - 14, `Left to right: ${row.label}`, {size: 12});
    c.room(x0, y, row.room * s, 36, {exact: row.exact});
    c.box(x0 + row.x * s, y + 4, row.width * s, 28);
    c.startEdge(x0, y, 36, false);
    c.text(x0 + Math.max(row.room, row.width) * s + 14, y + 23, row.note, {size: 12});
    y += 86;
  }
  const scale = 1;
  const room = fig('F3.rtlRoom') * scale;
  c.text(x0, y - 14, `Right to left: width(${fig('F3.rtlWidth')}), exactly ${fig('F3.rtlRoom')} wide`, {size: 12});
  c.room(x0, y, room, 36, {exact: true});
  c.box(x0 + fig('F3.rtlX') * scale, y + 4, fig('F3.rtlWidth') * scale, 28);
  c.startEdge(x0 + room, y, 36, true);
  c.dimension(x0, x0 + fig('F3.rtlX') * scale, y + 52, `x = ${fig('F3.rtlX')}, from the left`);
  c.text(x0 + room + 14, y + 23, `at the start edge, ${fig('F3.rtlRoom') - fig('F3.rtlWidth')} short of the end`, {size: 12});
  return c;
}

/** F4: place and placeRelative, including a declared width that differs from the final one. */
function placeRelative(fig) {
  const s = 0.38;
  const c = new Canvas(720, 470, 'place and placeRelative');
  const x0 = 100;
  const container = fig('F4.container') * s;
  const child = fig('F4.child') * s;
  const notes = x0 + container + 12;
  const rows = [
    {label: 'Left to right, either call', x: fig('F4.ltrX'), rtl: false},
    {label: 'Right to left, placeRelative', x: fig('F4.rtlRelativeX'), rtl: true},
    {label: 'Right to left, place', x: fig('F4.rtlPlaceX'), rtl: true},
  ];
  c.text(20, 20, `A container ${fig('F4.container')} wide, a child ${fig('F4.child')} wide: placeRelative(start = ${fig('F4.offset')}) or place(x = ${fig('F4.offset')})`, {size: 12});
  let y = 64;
  for (const row of rows) {
    c.text(x0, y - 10, row.label, {size: 12});
    c.frame(x0, y, container, 28);
    c.box(x0 + row.x * s, y + 4, child, 20);
    c.startEdge(row.rtl ? x0 + container : x0, y, 28, row.rtl);
    c.text(notes, y + 19, `x = ${row.x}`, {size: 12});
    y += 62;
  }
  c.text(20, y + 4, 'Right to left, when the final width differs from the declared one: the declared box (dotted) starts at the start edge', {size: 12});
  y += 50;
  const stretched = fig('F4.stretchedDeclared') * s;
  c.text(x0, y - 10, `Declared ${fig('F4.stretchedDeclared')}, stretched to ${fig('F4.container')}`, {size: 12});
  c.frame(x0, y, container, 28);
  c.ghost(x0 + container - stretched, y - 3, stretched, 34);
  c.box(x0 + fig('F4.stretchedX') * s, y + 4, child, 20);
  c.startEdge(x0 + container, y, 28, true);
  c.text(notes, y + 19, `either call: x = ${fig('F4.stretchedX')}`, {size: 12});
  y += 72;
  const narrowed = fig('F4.narrowedDeclared') * s;
  c.text(x0, y - 10, `Declared ${fig('F4.narrowedDeclared')}, kept to ${fig('F4.container')}`, {size: 12});
  c.ghost(x0 + container - narrowed, y - 3, narrowed, 34);
  c.frame(x0, y, container, 28);
  c.box(x0 + fig('F4.narrowedPlaceX') * s, y + 4, child, 20);
  c.box(x0 + fig('F4.narrowedRelativeX') * s, y + 4, child, 20);
  c.startEdge(x0 + container, y, 28, true);
  c.text(x0 + fig('F4.narrowedPlaceX') * s, y + 50, `place(0, 0): x = ${fig('F4.narrowedPlaceX')}`, {size: 12});
  c.text(notes, y + 19, `placeRelative(0, 0): x = ${fig('F4.narrowedRelativeX')}`, {size: 12});
  return c;
}

/** F5: the declared size is a request. */
function request(fig) {
  const s = 0.6;
  const c = new Canvas(720, 330, 'The declared size is a request');
  const rows = [
    {
      label: `A stretching Column ${fig('F5.stretchParent')} wide`,
      parent: fig('F5.stretchParent'),
      exact: true,
      declared: fig('F5.stretchDeclared'),
      final: fig('F5.stretchFinal'),
      note: `${fig('F5.stretchFinal')}, and the function runs again`,
    },
    {
      label: `A Column ${fig('F5.pastRoom')} wide that doesn't stretch it`,
      parent: fig('F5.pastRoom'),
      exact: false,
      declared: fig('F5.pastDeclared'),
      final: fig('F5.pastFinal'),
      note: `${fig('F5.pastFinal')}, past the room`,
    },
    {
      label: `The same, with maxWidth(${fig('F5.cappedMax')}) on the Layout`,
      parent: fig('F5.pastRoom'),
      exact: false,
      declared: fig('F5.pastDeclared'),
      final: fig('F5.cappedFinal'),
      note: `${fig('F5.cappedFinal')}, its own max`,
    },
    {
      label: `A stretching Column ${fig('F5.exactRoom')} wide`,
      parent: fig('F5.exactRoom'),
      exact: true,
      declared: fig('F5.pastDeclared'),
      final: fig('F5.exactFinal'),
      note: `${fig('F5.exactFinal')}, the exact room`,
    },
  ];
  c.text(20, 20, 'The declared size (dotted) and the size the parent gives (filled), under a Yoga parent', {size: 12});
  const x0 = 40;
  let y = 66;
  for (const row of rows) {
    c.text(x0, y - 14, `${row.label}: declared ${row.declared} → ${row.note}`, {size: 12});
    c.room(x0, y - 4, row.parent * s, 36, {exact: row.exact});
    c.box(x0, y + 2, row.final * s, 24);
    c.ghost(x0, y, row.declared * s, 28);
    y += 72;
  }
  return c;
}

/** F6: a child's own style against its room, as small multiples. */
function ownStyle(fig) {
  const c = new Canvas(720, 470, "A child's own style against its room");
  const room = fig('F6.room');
  const s = 0.5;
  const cells = [
    {rule: 'C3', style: `minWidth(${fig('F6.c3StretchMin')})`, exact: true, room, width: fig('F6.c3StretchResult'), result: `${fig('F6.c3StretchResult')}: fills the exact room`},
    {rule: 'C3', style: `minWidth(${fig('F6.c3PastMin')})`, exact: false, room, width: fig('F6.c3PastResult'), result: `${fig('F6.c3PastResult')}: past the room`},
    {rule: 'C4', style: `maxWidth(${fig('F6.c4Max')})`, exact: true, room, width: fig('F6.c4Result'), result: `${fig('F6.c4Result')}: the max wins`},
    {rule: 'C6', style: `width(${fig('F6.c6Width')})`, exact: true, room, width: fig('F6.c6Result'), result: `${fig('F6.c6Result')}: its own width`},
    {rule: 'C6', style: `width(${fig('F6.c6Width')}).minWidth(${fig('F6.c6MinWidth')})`, exact: true, room, width: fig('F6.c6MinResult'), result: `${fig('F6.c6MinResult')}: within its own min`},
    {rule: 'C7', style: `widthPercent(${fig('F6.c7HalfPercent')})`, exact: true, room, width: fig('F6.c7HalfResult'), result: `${fig('F6.c7HalfResult')}: of the room`},
    {rule: 'C7', style: `widthPercent(${fig('F6.c7OverPercent')})`, exact: false, room, width: fig('F6.c7OverMeasured'), result: `measuredWidth ${fig('F6.c7OverMeasured')}, width ${fig('F6.c7OverWidth')}`},
    {rule: 'C9', style: `padding(all = ${fig('F6.c9Padding')})`, exact: true, room: fig('F6.c9Room'), width: fig('F6.c9Result'), result: `${fig('F6.c9Result')}: its padding (drawn ×4)`, scale: 4},
  ];
  const columnWidth = 350;
  cells.forEach((cell, index) => {
    const x = 20 + (index % 2) * columnWidth;
    const y = 40 + Math.floor(index / 2) * 108;
    const scale = cell.scale ?? s;
    c.text(x, y, `${cell.rule} · ${cell.style}`, {mono: true, size: 12});
    c.text(x, y + 16, cell.exact ? `exactly ${cell.room} wide` : `at most ${cell.room} wide`, {muted: true, size: 12});
    c.room(x, y + 28, cell.room * scale, 30, {exact: cell.exact});
    c.box(x, y + 32, cell.width * scale, 22);
    c.startEdge(x, y + 28, 30, false);
    c.text(x, y + 76, `→ ${cell.result}`, {size: 12});
  });
  return c;
}

/** F7: ActionBarLayout shows its labelled buttons when they fit, and its icons when they don't. */
function actionBar(fig) {
  const s = 3;
  const c = new Canvas(720, 250, "ActionBarLayout's two outcomes");
  const x0 = 40;
  const labelled = fig('F7.labelledWidth');
  const icons = fig('F7.iconWidth');
  const rows = [
    {room: fig('F7.fitsRoom'), shows: 'labelled', width: labelled, height: fig('F7.labelledHeight'), note: `labelled, ${labelled} wide, fits: shown`},
    {room: fig('F7.tightRoom'), shows: 'icons', width: icons, height: fig('F7.iconHeight'), note: `labelled, ${labelled} wide, doesn't fit: icons, ${icons} wide, shown`},
  ];
  let y = 46;
  for (const row of rows) {
    c.text(x0, y - 14, `At most ${row.room} wide: ${row.note}`, {size: 12});
    c.room(x0, y, row.room * s, row.height * s + 8, {});
    c.box(x0, y + 4, row.width * s, row.height * s, row.shows);
    if (row.shows === 'icons') {
      c.ghost(x0, y + 2, labelled * s, row.height * s + 4, null);
    }
    c.startEdge(x0, y, row.height * s + 8, false);
    y += 120;
  }
  c.text(x0, y - 40, 'Dotted: the labelled buttons, measured under an unbounded width and not placed', {muted: true, size: 12});
  return c;
}

const FIGURES = {
  'custom-layout-anatomy': anatomy,
  'custom-layout-placeable': placeable,
  'custom-layout-start-edge': startEdge,
  'custom-layout-place-relative': placeRelative,
  'custom-layout-request': request,
  'custom-layout-own-style': ownStyle,
  'custom-layout-action-bar': actionBar,
};

/** Copies each screenshot golden into the website's images, or with [check] reports a stale copy. */
function copyScreenshots(check, stale) {
  for (const name of fs.readdirSync(SCREENSHOTS).filter(file => file.endsWith('.png')).sort()) {
    const source = path.join(SCREENSHOTS, name);
    const target = path.join(OUT, name);
    if (check) {
      if (!fs.existsSync(target) || !fs.readFileSync(source).equals(fs.readFileSync(target))) {
        stale.push(path.relative(ROOT, target));
      }
    } else {
      fs.copyFileSync(source, target);
    }
  }
}

/** The custom Layout images the docs use that don't exist. */
function missingImages() {
  const missing = [];
  for (const doc of DOCS) {
    const source = fs.readFileSync(path.join(ROOT, doc), 'utf8');
    for (const match of source.matchAll(/\/images\/(custom-layout-[\w-]+\.(?:svg|png))/g)) {
      if (!fs.existsSync(path.join(OUT, match[1]))) {
        missing.push(`${doc}: ${match[1]}`);
      }
    }
  }
  return missing;
}

function main() {
  const check = process.argv.includes('--check');
  const fig = readFigures();
  const stale = [];
  copyScreenshots(check, stale);
  for (const [name, draw] of Object.entries(FIGURES)) {
    const canvas = draw(fig);
    for (const [theme, suffix] of [
      ['light', ''],
      ['dark', '-dark'],
    ]) {
      const file = path.join(OUT, `${name}${suffix}.svg`);
      const svg = canvas.render(theme);
      if (check) {
        const current = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
        if (current !== svg) {
          stale.push(path.relative(ROOT, file));
        }
      } else {
        fs.writeFileSync(file, svg);
      }
    }
  }
  if (stale.length > 0) {
    console.error(
      `Out of date; run node website/scripts/custom-layout-figures.js:\n  ${stale.join('\n  ')}`,
    );
    process.exit(1);
  }
  const missing = missingImages();
  if (missing.length > 0) {
    console.error(`Images the docs use that don't exist:\n  ${missing.join('\n  ')}`);
    process.exit(1);
  }
}

main();
