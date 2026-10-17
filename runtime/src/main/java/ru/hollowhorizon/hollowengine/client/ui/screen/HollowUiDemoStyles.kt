package ru.hollowhorizon.hollowengine.client.ui.screen

import ru.hollowhorizon.hollowengine.client.ui.style.compileHss

internal val DemoStyles = compileHss(
    """
    #demo-root {
        size: 100% 100%;
        min-size: 0px 0px;
        padding: 14px;
        gap: 10px;
        background: rgba(8, 10, 14, 0.92);
    }

    .tabs {
        align: start;
        gap: 8px;
        height: 47px;
        min-size: 0px 47px;
    }

    .tab {
        padding: 6px 10px;
        gap: 6px;
        width: 104px;
        background: rgba(28, 32, 42, 0.92);
        border-radius: 8px;
        border: 1px rgba(120, 140, 170, 0.38);
        hoverable: true;
        clickable: true;
        transition:
            scale 140ms ease-out,
            background 140ms ease-out;
    }

    .tab:hover {
        background: rgba(42, 48, 62, 0.96);
        scale: 1.02;
    }

    .tab:selected {
        background: rgba(54, 72, 108, 0.98);
        border: 1px rgba(145, 178, 230, 0.9);
    }

    .tab-icon {
        size: 18px 18px;
        fit: contain;
    }

    .tab-label {
        foreground: white;
    }

    .content {
        grow: 1;
        size: 100% 100%;
        min-size: 0px 0px;
        background: rgba(18, 20, 27, 0.88);
        border-radius: 12px;
        border: 1px rgba(110, 125, 150, 0.32);
        padding: 12px;
        clip: true;
    }

    .panel {
        gap: 10px;
        size: 100% 100%;
        min-size: 0px 0px;
    }

    .scroll-panel {
        scrollable: true;
        size: 100% 100%;
        min-size: 0px 0px;
    }

    .title {
        foreground: #c8ddff;
        height: 18px;
    }

    .body {
        foreground: rgba(226, 230, 238, 0.92);
    }

    .row {
        gap: 8px;
        padding: 8px;
        height: 48px;
        width: 100%;
        background: rgba(32, 36, 46, 0.78);
        border-radius: 8px;
    }

    .small-icon {
        size: 20px 20px;
    }

    .panel-grid {
        size: 100% 100%;
        min-size: 0px 0px;
        gap: 10px;
        scrollable: true;
    }

    .card {
        gap: 8px;
        padding: 10px;
        size: 168px 118px;
        background: rgba(30, 34, 44, 0.9);
        border-radius: 10px;
        border: 1px rgba(120, 140, 170, 0.35);
        transition:
            scale 140ms ease-out,
            rotate 140ms ease-out,
            background 140ms ease-out;
    }

    .card:hover {
        background: rgba(42, 48, 62, 0.96);
        scale: 1.1;
    }

    .card-title {
        foreground: #c8ddff;
        height: 16px;
    }

    .tilted-x {
        transition:
            rotate 180ms ease-out,
            scale 90ms ease-out,
            background 120ms ease-out;
    }

    .preview-image {
        size: 72px 72px;
        image-fit: contain;
        shadow: 0px 0px 5px #000000;
    }

    .item-preview {
        size: 52px 52px;
    }

    .entity-preview {
        size: 72px 72px;
    }

    .visual-preview {
        size: 120px 54px;
    }

    .text-demo-stage {
        size: 100% 100%;
        min-size: 0px 0px;
        scrollable: true;
    }

    .text-demo-card {
        gap: 8px;
        padding: 12px;
        size: 330px 140px;
        background: rgba(30, 34, 44, 0.9);
        border-radius: 10px;
        border: 1px rgba(120, 140, 170, 0.35);
    }

    .text-slot-card {
        size: 354px 140px;
    }

    .popup-demo-card {
        size: 330px 172px;
    }

    .text-demo-copy {
        foreground: rgba(226, 230, 238, 0.92);
        text-wrap: true;
        line-spacing: 3px;
    }

    .text-inline-chip {
        padding: 2px 8px;
        size: auto 22px;
        background: rgba(90, 166, 154, 0.96);
        border-radius: 6px;
        border: 1px rgba(182, 255, 244, 0.62);
    }

    .text-inline-chip-label {
        foreground: white;
        font-size: 9px;
        height: 14px;
    }

    .text-slot-note {
        padding: 6px;
        size: 72px 46px;
        background: rgba(238, 244, 255, 0.94);
        foreground: rgba(24, 32, 46, 0.98);
        border-radius: 8px;
        border: 1px rgba(255, 255, 255, 0.66);
    }

    .text-slot-note-label {
        foreground: rgba(24, 32, 46, 0.98);
        font-size: 9px;
    }

    .textflow-card {
        size: 354px 172px;
    }

    .textflow-para {
        size: 330px 128px;
        foreground: rgba(226, 230, 238, 0.92);
        align-items: justify start;
        line-spacing: 4px;
        font-size: 10px;
    }

    .group-slice {
        background: rgba(90, 166, 154, 0.42);
        border-radius: 4px;
        padding: 1px 5px;
        box-decoration-break: slice;
        foreground: #eafff9;
    }

    .group-clone {
        background: rgba(120, 128, 214, 0.5);
        border-radius: 5px;
        padding: 1px 6px;
        box-decoration-break: clone;
        foreground: #eef1ff;
    }

    .textflow-chip {
        padding: 1px 6px;
        margin: 0px 3px;
        background: rgba(90, 120, 200, 0.92);
        border-radius: 5px;
    }

    .textflow-chip-label {
        foreground: white;
        font-size: 9px;
        height: 12px;
    }

    .textflow-caret {
        size: 2px 12px;
        margin: 0px 1px;
        background: #ffd479;
    }

    .textlab-panel {
        size: 100% 100%;
        gap: 10px;
        padding: 12px;
    }

    .textlab-controls {
        gap: 10px;
        height: 20px;
        align-items: start center;
    }

    .textlab-toggle {
        gap: 5px;
        padding: 2px 6px;
        background: rgba(40, 46, 58, 0.8);
        border-radius: 6px;
        align-items: start center;
    }

    .textlab-toggle-label {
        foreground: #c8ddff;
    }

    .textlab-check {
        size: 12px 12px;
        border-radius: 3px;
    }

    .textlab-hint {
        foreground: rgba(180, 190, 205, 0.8);
    }

    .textlab-stage-row {
        gap: 0px;
    }

    .textlab-card {
        gap: 8px;
        padding: 12px;
        background: rgba(30, 34, 44, 0.9);
        border-radius: 10px;
        border: 1px rgba(120, 140, 170, 0.35);
    }

    .textlab-handle {
        size: 8px 100%;
        min-size: 8px 60px;
        background: rgba(120, 140, 170, 0.5);
        border-radius: 4px;
    }

    .textlab-handle:hover {
        background: rgba(150, 175, 215, 0.85);
    }

    .textlab-para {
        foreground: rgba(226, 230, 238, 0.92);
        line-spacing: 4px;
        font-size: 10px;
    }

    .textlab-section {
        margin: 6px 0px 0px 0px;
    }

    .textlab-scroll {
        size: 360px 70px;
        padding: 8px;
        background: rgba(24, 28, 38, 0.9);
        border-radius: 8px;
    }

    .textlab-catalog {
        gap: 3px;
        padding: 6px;
        background: rgba(24, 28, 38, 0.75);
        border-radius: 8px;
    }

    .textlab-effect-row {
        gap: 8px;
        align-items: start center;
        padding: 2px 4px;
        border-radius: 5px;
    }

    .textlab-effect-row:hover {
        background: rgba(70, 84, 110, 0.35);
    }

    .textlab-effect-name {
        size: 118px auto;
    }

    .textlab-params {
        gap: 6px;
        size: 300px auto;
        align-items: start center;
    }

    .textlab-effect-preview {
        foreground: rgba(226, 230, 238, 0.95);
    }

    .textlab-field {
        gap: 4px;
        align-items: start center;
    }

    .textlab-label {
        foreground: rgba(170, 182, 200, 0.9);
        font-size: 9px;
    }

    .textlab-input {
        padding: 1px 4px;
        background: rgba(16, 19, 26, 0.9);
        border: 1px rgba(110, 128, 158, 0.5);
        border-radius: 4px;
        foreground: #e6ebf5;
    }

    .textlab-sample-field {
        size: 520px auto;
        padding: 2px 5px;
        background: rgba(16, 19, 26, 0.9);
        border: 1px rgba(110, 128, 158, 0.5);
        border-radius: 4px;
        foreground: #e6ebf5;
    }

    .textlab-stage {
        padding: 8px;
        background: rgba(30, 34, 44, 0.9);
        border-radius: 8px;
        border: 1px rgba(120, 140, 170, 0.35);
        clip: true;
    }

    .textlab-handle-h {
        size: 100% 8px;
        min-size: 60px 8px;
        margin: 2px 0px 0px 0px;
        background: rgba(120, 140, 170, 0.5);
        border-radius: 4px;
    }

    .textlab-handle-h:hover {
        background: rgba(150, 175, 215, 0.85);
    }

    .textlab-table {
        gap: 2px;
        padding: 6px;
        background: rgba(24, 28, 38, 0.75);
        border-radius: 8px;
    }

    .textlab-row {
        gap: 10px;
        align-items: start center;
        padding: 2px 4px;
        border-radius: 5px;
    }

    .textlab-row:hover {
        background: rgba(70, 84, 110, 0.3);
    }

    .textlab-head {
        foreground: rgba(150, 165, 185, 0.9);
        font-size: 9px;
    }

    .textlab-cell-name {
        size: 150px auto;
        foreground: rgba(180, 195, 215, 0.9);
        font-size: 9px;
    }

    .textlab-cell {
        size: 170px auto;
        foreground: rgba(226, 230, 238, 0.95);
    }

    .textlab-mixed {
        size: 100% auto;
        margin: 0px 0px 12px 0px;
    }

    .textlab-chip {
        padding: 1px 6px;
        margin: 0px 3px;
        background: rgba(90, 120, 200, 0.92);
        border-radius: 5px;
    }

    .textlab-chip-label {
        foreground: white;
        font-size: 9px;
    }

    .fx-big {
        font-size: 16px;
        foreground: #ffd479;
    }

    .fx-bold {
        text-effects: bold;
    }

    .fx-italic {
        text-effects: italic;
        foreground: #a9d0ff;
    }

    .fx-underline {
        text-effects: underline;
    }

    .fx-strike {
        text-effects: strikethrough;
        foreground: rgba(226, 230, 238, 0.7);
    }

    .fx-heavy {
        text-effects: bold(0.14);
        foreground: #ffd479;
    }

    .fx-oblique {
        text-effects: italic(22);
        foreground: #a9d0ff;
    }

    .fx-thick-rule {
        text-effects: underline(0.09, 0.04, #ff6f91);
    }

    .fx-wave {
        text-effects: wave;
        foreground: #7fe0c0;
    }

    .fx-shake {
        text-effects: shake;
        foreground: #ff9d7f;
    }

    .fx-rainbow {
        text-effects: rainbow;
    }

    .fx-msdf {
        font-family: hollowengine:fonts/hack;
        foreground: #ffd479;
    }

    .popup-anchor {
        padding: 7px 12px;
        width: 156px;
        height: 28px;
        background: rgba(72, 150, 142, 0.96);
        border-radius: 7px;
        border: 1px rgba(184, 255, 244, 0.68);
    }

    .popup-anchor-label {
        foreground: white;
    }

    .popup-panel {
        gap: 4px;
        padding: 9px;
        size: 176px auto;
        background: rgba(244, 248, 255, 0.96);
        foreground: rgba(26, 34, 48, 0.98);
        border-radius: 8px;
        border: 1px rgba(255, 255, 255, 0.7);
        shadow: 0px 14px 26px -8px rgba(0, 0, 0, 0.52);
    }

    .cursor-popup {
        size: 172px auto;
    }

    .popup-title {
        foreground: rgba(24, 38, 58, 0.98);
        height: 16px;
    }

    .popup-body {
        foreground: rgba(58, 68, 84, 0.92);
    }

    .free-stage {
        size: 100% 100%;
        min-size: 0px 0px;
        background: linear-gradient(225deg, rgba(12, 16, 23, 0.94), rgba(18, 44, 54, 0.9), rgba(38, 42, 64, 0.88));
        border-radius: 10px;
        scrollable: true;
    }

    .free-node {
        padding: 8px;
        size: 92px 42px;
        background: rgba(48, 62, 88, 0.95);
        border-radius: 8px;
        border: 1px rgba(136, 174, 230, 0.65);
        transition:
            scale 180ms ease-out,
            background 180ms ease-out;
    }

    .free-node:hover {
        scale: 1.03;
        background: rgba(62, 78, 110, 0.98);
    }

    .free-node:dragging {
        scale: 1.08;
        background: rgba(78, 96, 134, 1.0);
    }

    .free-label {
        foreground: white;
        height: 16px;
    }

    .layout-glass {
        gap: 8px;
        padding: 12px;
        size: 184px 92px;
        border-radius: 12px;
        border: 1px rgba(198, 222, 250, 0.34);
        background: rgba(28, 36, 50, 0.28);
        foreground: rgba(226, 238, 255, 0.95);
        backdrop-filter: blur(9px) grayscale(0.08);
        shadow: none;
        draggable: true;
        hoverable: true;
        transition:
            translate 120ms ease-out,
            shadow 160ms ease-out,
            background 140ms ease-out;
    }

    .layout-glass:hover {
        background: rgba(42, 56, 76, 0.34);
        shadow: none;
    }

    .editor-demo-stage {
        size: 100% 100%;
        min-size: 0px 0px;
        scrollable: true;
    }

    .editor-demo-card {
        gap: 8px;
        padding: 12px;
        size: 532px 268px;
        background: rgba(24, 29, 39, 0.94);
        border-radius: 10px;
        border: 1px rgba(120, 140, 170, 0.36);
    }

    .editor-text-field {
        padding: 9px;
        background: rgba(10, 13, 18, 0.96);
        foreground: rgba(226, 232, 240, 0.96);
        border: 1px rgba(112, 134, 166, 0.48);
        border-radius: 8px;
        font-size: 10px;
        line-spacing: 3px;
        caret-color: #f4f7ff;
        selection-color: rgba(92, 150, 255, 0.36);
        line-number-color: rgba(130, 146, 170, 0.76);
        line-numbers: true;
        inlay-hints: true;
    }

    .editable-text-field-inlay-text {
        foreground: rgba(130, 146, 170, 0.58);
    }

    .editor-key-log {
        foreground: rgba(160, 184, 220, 0.92);
        height: 16px;
    }

    .code-editor-inlay {
        height: fit;
        padding: 0px 3px;
        align-items: center center;
        background: #1E1F22;
        margin-left: 2px;
        margin-right: 4px;
        border-radius: 3px;
    }

    .code-editor-inlay-text {
        foreground: rgba(142, 153, 171, 0.9);
        text-wrap: false;
    }

    .lazy-column-card {
        size: 260px 268px;
    }

    .lazy-column-demo {
        size: 100% 216px;
        min-size: 0px 0px;
        clip: true;
    }

    .lazy-list-row {
        height: 32px;
        padding: 6px 8px;
        gap: 8px;
        background: rgba(34, 40, 52, 0.86);
        border-radius: 6px;
        border: 1px rgba(116, 136, 164, 0.22);
    }

    .lazy-row-index {
        width: 30px;
        foreground: rgba(120, 190, 180, 0.96);
    }

    .lazy-row-card {
        size: 812px 144px;
    }

    .lazy-row-demo {
        size: 100% 88px;
        min-size: 0px 0px;
        clip: true;
    }

    .lazy-row-tile {
        gap: 4px;
        padding: 9px;
        size: 72px 68px;
        background: rgba(38, 46, 62, 0.92);
        border-radius: 8px;
        border: 1px rgba(130, 154, 188, 0.34);
    }

    .lazy-row-tile-title {
        foreground: #c8ddff;
        height: 16px;
    }

    .effects-stage {
        size: 100% 100%;
        min-size: 0px 0px;
        scrollable: true;
        background: linear-gradient(225deg, rgba(12, 18, 28, 0.98), rgba(28, 34, 48, 0.94), rgba(18, 42, 48, 0.92));
        border-radius: 10px;
    }

    .effect-card {
        gap: 8px;
        padding: 12px;
        size: 178px 126px;
        border-radius: 14px;
        border: 2px rgba(232, 238, 255, 0.34);
        background: rgba(32, 39, 54, 0.9);
        foreground: rgba(238, 242, 250, 0.96);
        shadow: 0px 18px 14px -4px rgba(0, 0, 0, 0.66);
        transition:
            filter 180ms ease-out,
            rotate 240ms ease-out,
            translate 180ms ease-out,
            shadow 180ms ease-out,
            scale 140ms ease-out,
            background 160ms ease-out;
    }

    .gradient-card {
        background: linear-gradient(225deg, rgba(70, 132, 218, 0.96), rgba(142, 83, 184, 0.94), rgba(34, 172, 148, 0.9));
        shadow: 0px 22px 40px -6px rgba(8, 18, 34, 0.72);
    }

    .grayscale-card {
        filter: grayscale(1);
        background: linear-gradient(235deg, rgba(86, 112, 152, 0.94), rgba(42, 52, 72, 0.96));
        border: 2px rgba(218, 230, 246, 0.5);
        hoverable: true;
    }

    .grayscale-card:hover {
        filter: grayscale(0);
        scale: 1.04;
        border: 2px rgba(218, 230, 246, 0.5);
    }

    .flip-zone {
        size: 178px 126px;
        hoverable: true;
        perspective: 680px;
    }

    .flip-face {
        backface-visibility: hidden;
        perspective: 680px;
        shadow: none;
        transition:
            rotate 460ms ease-out,
            shadow 380ms ease-out;
    }

    .flip-front {
        background: linear-gradient(225deg, rgba(58, 102, 156, 0.97), rgba(36, 48, 74, 0.97));
    }

    .flip-back {
        background: linear-gradient(225deg, rgba(38, 154, 130, 0.96), rgba(38, 62, 78, 0.98));
        border: 2px rgba(174, 255, 228, 0.44);
    }

    .paper-card {
        hoverable: true;
        perspective: 720px;
        background: linear-gradient(to left, rgba(242, 235, 212, 0.96), rgba(206, 218, 230, 0.94));
        foreground: rgba(22, 28, 36, 0.98);
        border: 2px rgba(255, 255, 255, 0.62);
    }

    .paper-card:hover {
        rotate: -12deg 10deg 0deg;
        translate: 0px -8px 18px;
    }

    .paper-title {
        foreground: rgba(28, 34, 48, 0.98);
    }

    .paper-body {
        foreground: rgba(48, 54, 68, 0.94);
    }

    .glass-card {
        background: rgba(34, 42, 58, 0.42);
        border: 2px rgba(232, 246, 255, 0.38);
        backdrop-filter: blur(16px) grayscale(0.15);
    }

    .css-lift-card {
        hoverable: true;
        perspective: 800px;
        rotate: 51deg 0deg 43deg;
        background: rgba(248, 250, 253, 0.96);
        foreground: rgba(30, 34, 52, 0.96);
        border: 1px rgba(255, 255, 255, 0.72);
        transition:
            translate 240ms ease-out,
            rotate 240ms ease-out;
    }

    .css-lift-card:hover {
        translate: 0px -16px 0px;
    }

    .soft-focus-card {
        hoverable: true;
        perspective: 800px;
        rotate: 10deg -25deg 0deg;
        scale: 0.9;
        opacity: 0.55;
        filter: blur(8px);
        background: linear-gradient(225deg, rgba(236, 126, 168, 0.92), rgba(126, 154, 246, 0.92));
        foreground: rgba(246, 248, 255, 0.96);
        shadow: 24px 24px 34px -8px rgba(12, 12, 28, 0.68);
        transition:
            filter 260ms ease-out,
            opacity 260ms ease-out,
            scale 260ms ease-out,
            rotate 260ms ease-out;
    }

    .soft-focus-card:hover {
        rotate: 10deg 15deg 0deg;
        scale: 1;
        opacity: 1;
        filter: blur(0px);
    }

    .soft-title {
        foreground: rgba(250, 252, 255, 0.98);
    }

    .soft-body {
        foreground: rgba(250, 252, 255, 0.94);
    }

    .shapes-stage {
        size: 100% 100%;
        min-size: 0px 0px;
        scrollable: true;
        background: linear-gradient(225deg, rgba(12, 16, 24, 0.98), rgba(26, 34, 46, 0.94), rgba(22, 50, 54, 0.92));
        border-radius: 10px;
    }

    .shape-card {
        gap: 8px;
        padding: 12px;
        size: 188px 126px;
        foreground: rgba(238, 244, 252, 0.96);
    }

    .hss-path-card {
        padding: 24px 12px 12px 32px;
        shape: path("M 0 0 L 188 0 C 176 32 190 72 156 126 L 0 126 Q 24 64 0 0 Z", 188 126);
        shape-fill: radial-gradient(72% at 32% 28%, rgba(92, 204, 190, 0.98), rgba(58, 86, 156, 0.96), rgba(28, 32, 52, 0.94));
        shape-stroke: rgba(224, 246, 255, 0.74);
        shape-stroke-width: 2px;
    }

    .shape-label {
        foreground: rgba(244, 248, 255, 0.98);
        height: 16px;
    }

    .shape-clip-card {
        padding: 0px;
    }

    .shape-clip-stripe {
        border-radius: 0px;
    }

    .shape-clip-stripe-a {
        background: rgba(255, 255, 255, 0.24);
    }

    .shape-clip-stripe-b {
        background: linear-gradient(to bottom, rgba(255, 230, 128, 0.72), rgba(255, 122, 154, 0.52));
    }

    .shape-clip-stripe-c {
        background: rgba(18, 26, 44, 0.34);
    }

    .svg-file-hexagon {
        shape: svg("hollowengine:ui/shapes/hexagon.svg");
        shape-fill: linear-gradient(225deg, rgba(255, 206, 94, 0.88), rgba(92, 204, 190, 0.86), rgba(58, 86, 156, 0.92));
        shape-stroke: rgba(242, 250, 255, 0.82);
        shape-stroke-width: 2px;
    }

    .svg-clip-card {
        padding: 0px;
    }
    """.trimIndent()
)
