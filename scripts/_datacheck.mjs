import fs from "node:fs";
const d=JSON.parse(fs.readFileSync("E:\\duihuazhushou\\data\\app-data.json","utf8"));
const b=JSON.parse(fs.readFileSync("E:\\duihuazhushou\\data\\.clean-baseline.json","utf8"));
const do_=Object.keys(d.origins||{}), bo=Object.keys(b.origins||{});
console.log("当前来源切片:", do_.join(" | "));
console.log("基线来源切片:", bo.join(" | "));
const real=["192.168.31.45:5173","localhost:5173","localhost:5174","192.168.31.45:5174"];
let bad=0;
for(const o of real){
  const a=JSON.stringify(d.origins?.[o]??null), c=JSON.stringify(b.origins?.[o]??null);
  const same=a===c; if(!same) bad++;
  console.log(`  ${same?"✓ 逐字节一致":"✗ 不一致"}  ${o}`);
}
const extra=do_.filter(o=>!bo.includes(o));
console.log("测试期间新增来源切片:", extra.length?extra.join(" | "):"无");
console.log(bad?"!!! 真实数据被改动 !!!":"真实数据完好");
process.exitCode=bad?1:0;
