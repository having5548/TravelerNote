// 手机端矢量图标 → 手表端 PNG 转换脚本（一次性工具）。
// 重跑：npm i --no-save @resvg/resvg-js && node scripts/gen-icons.js
// 路径数据原样取自手机端 app/src/main/res/drawable/ic_*.xml（白色 24x24）。
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');

const ICONS = {
  resin:
    'M12,2c-5.33,4.55 -8,8.48 -8,11.8c0,4.98 3.8,8.2 8,8.2s8,-3.22 8,-8.2c0,-3.32 -2.67,-7.25 -8,-11.8z',
  home_coin:
    'M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12.88,17.76L12.88,19l-1.76,0 0,-1.24c-1.36,-0.21 -2.48,-0.91 -3.17,-1.82l1.31,-1.31c0.66,0.77 1.6,1.24 2.79,1.24 0.85,0 1.47,-0.39 1.47,-1.09 0,-0.54 -0.35,-0.9 -1.5,-1.32l-0.82,-0.3c-1.75,-0.64 -2.91,-1.54 -2.91,-3.09 0,-1.47 1.09,-2.63 2.83,-2.88L11.12,5l1.76,0 0,1.19c1.02,0.17 1.9,0.62 2.49,1.26l-1.24,1.31c-0.5,-0.5 -1.25,-0.87 -2.14,-0.87 -0.82,0 -1.23,0.42 -1.23,0.96 0,0.54 0.33,0.86 1.45,1.27l0.84,0.3c1.88,0.67 2.93,1.66 2.93,3.2 0,1.55 -1.08,2.72 -2.9,2.9z',
  task:
    'M19,3h-4.18C14.4,1.84 13.3,1 12,1c-1.3,0 -2.4,0.84 -2.82,2L5,3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z',
  sign:
    'M16.53,11.06L15.47,10l-4.88,4.88 -2.12,-2.12 -1.06,1.06L10.59,17l5.94,-5.94zM19,3h-1L18,1h-2v2L8,3L8,1L6,1v2L5,3c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM19,19L5,19L5,8h14v11z'
};

const outDir = path.join(__dirname, '../src/common/icons');
fs.mkdirSync(outDir, { recursive: true });

for (const [name, d] of Object.entries(ICONS)) {
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' +
    `<path d="${d}" fill="#FFFFFF"/></svg>`;
  const png = new Resvg(svg, { fitTo: { mode: 'width', value: 128 } })
    .render()
    .asPng();
  fs.writeFileSync(path.join(outDir, `${name}.png`), png);
  console.log(`[gen-icons] ${name}.png (${png.length} bytes)`);
}
